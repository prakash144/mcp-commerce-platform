package main

import (
	"context"
	"fmt"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/commerce/payment-service/api"
	"github.com/commerce/payment-service/internal/config"
	"github.com/commerce/payment-service/internal/handler"
	"github.com/commerce/payment-service/internal/middleware"
	"github.com/commerce/payment-service/internal/model"
	"github.com/commerce/payment-service/internal/observability"
	"github.com/commerce/payment-service/internal/repository"
	"github.com/commerce/payment-service/internal/server"
	"github.com/commerce/payment-service/internal/service"
	paymentv1 "github.com/commerce/payment-service/pkg/generated"
	"github.com/grpc-ecosystem/grpc-gateway/v2/runtime"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	gormlogger "gorm.io/gorm/logger"
)

func main() {
	sl := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(sl)
	// keep std logger for startup fatalf (gives a clear exit without verbosity)
	log.SetOutput(os.Stdout)

	cfg := config.Load()

	db, err := gorm.Open(postgres.Open(cfg.DSN()), &gorm.Config{
		Logger: gormlogger.Default.LogMode(gormlogger.Info),
	})
	if err != nil {
		slog.Error("failed to connect database", "error", err)
		os.Exit(1)
	}
	if err := db.AutoMigrate(&model.Payment{}, &model.Refund{}); err != nil {
		slog.Error("failed to migrate database", "error", err)
		os.Exit(1)
	}
	slog.Info("database connected and migrated")

	repo := repository.NewPaymentRepository(db)
	svc := service.NewPaymentService(repo)
	h := handler.NewPaymentHandler(svc)

	metrics := observability.NewMetrics()

	srv := server.New(cfg.GRPCPort,
		metrics.UnaryInterceptor(),
		middleware.UnaryRecovery(sl),
		middleware.UnaryLogging(sl),
	)
	srv.Register(h)

	go func() {
		slog.Info("gRPC server listening", "port", cfg.GRPCPort)
		if err := srv.Serve(); err != nil {
			slog.Error("gRPC server error", "error", err)
			os.Exit(1)
		}
	}()

	restServer := newRESTGateway(sl, cfg, metrics)
	go func() {
		slog.Info("REST gateway listening", "port", cfg.RESTPort)
		if err := restServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("REST gateway error", "error", err)
			os.Exit(1)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	slog.Info("shutting down gracefully")
	srv.GracefulStop()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := restServer.Shutdown(ctx); err != nil {
		slog.Error("REST shutdown error", "error", err)
	}
	slog.Info("server stopped")
}

func newRESTGateway(sl *slog.Logger, cfg *config.Config, metrics *observability.Metrics) *http.Server {
	ctx := context.Background()

	mux := runtime.NewServeMux()
	gwAddr := fmt.Sprintf("localhost:%d", cfg.GRPCPort)
	gwOpts := []grpc.DialOption{grpc.WithTransportCredentials(insecure.NewCredentials())}
	if err := registerPaymentService(ctx, mux, gwAddr, gwOpts); err != nil {
		slog.Error("failed to register REST gateway", "error", err)
		os.Exit(1)
	}

	mux.HandlePath("GET", "/metrics", func(w http.ResponseWriter, r *http.Request, _ map[string]string) {
		metrics.Handler().ServeHTTP(w, r)
	})
	mux.HandlePath("GET", "/swagger.json", func(w http.ResponseWriter, r *http.Request, _ map[string]string) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(api.SwaggerJSON)
	})
	mux.HandlePath("GET", "/docs", func(w http.ResponseWriter, r *http.Request, _ map[string]string) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(api.IndexHTML)
	})

	return &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.RESTPort),
		Handler:           allowCORS(mux),
		ReadHeaderTimeout: 10 * time.Second,
	}
}

func registerPaymentService(ctx context.Context, mux *runtime.ServeMux, target string, opts []grpc.DialOption) error {
	return paymentv1.RegisterPaymentServiceHandlerFromEndpoint(ctx, mux, target, opts)
}

func allowCORS(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Correlation-Id")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}
		h.ServeHTTP(w, r)
	})
}
