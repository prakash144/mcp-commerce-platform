package main

import (
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/commerce/payment-service/internal/config"
	"github.com/commerce/payment-service/internal/handler"
	"github.com/commerce/payment-service/internal/middleware"
	"github.com/commerce/payment-service/internal/model"
	"github.com/commerce/payment-service/internal/repository"
	"github.com/commerce/payment-service/internal/server"
	"github.com/commerce/payment-service/internal/service"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	gormlogger "gorm.io/gorm/logger"
)

func main() {
	logger := log.New(os.Stdout, "payment-service: ", log.LstdFlags)
	cfg := config.Load()

	db, err := gorm.Open(postgres.Open(cfg.DSN()), &gorm.Config{
		Logger: gormlogger.Default.LogMode(gormlogger.Info),
	})
	if err != nil {
		logger.Fatalf("failed to connect database: %v", err)
	}
	if err := db.AutoMigrate(&model.Payment{}, &model.Refund{}); err != nil {
		logger.Fatalf("failed to migrate database: %v", err)
	}
	logger.Println("database connected and migrated")

	repo := repository.NewPaymentRepository(db)
	svc := service.NewPaymentService(repo)
	h := handler.NewPaymentHandler(svc)

	srv := server.New(cfg.GRPCPort,
		middleware.UnaryRecovery(logger),
		middleware.UnaryLogging(logger),
	)
	srv.Register(h)

	go func() {
		logger.Printf("gRPC server listening on :%d", cfg.GRPCPort)
		if err := srv.Serve(); err != nil {
			logger.Fatalf("gRPC server error: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	logger.Println("shutting down gracefully")
	srv.GracefulStop()
	logger.Println("server stopped")
}
