package server

import (
	"fmt"
	"net"

	"github.com/commerce/payment-service/internal/handler"
	paymentv1 "github.com/commerce/payment-service/pkg/generated"
	"google.golang.org/grpc"
	"google.golang.org/grpc/health"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/reflection"
)

type Server struct {
	port   int
	grpc   *grpc.Server
	health *health.Server
}

func New(port int, interceptors ...grpc.UnaryServerInterceptor) *Server {
	opts := []grpc.ServerOption{grpc.ChainUnaryInterceptor(interceptors...)}
	s := grpc.NewServer(opts...)

	hs := health.NewServer()
	hs.SetServingStatus("", healthpb.HealthCheckResponse_SERVING)
	healthpb.RegisterHealthServer(s, hs)
	reflection.Register(s)

	return &Server{port: port, grpc: s, health: hs}
}

func (s *Server) Register(h *handler.PaymentHandler) {
	paymentv1.RegisterPaymentServiceServer(s.grpc, h)
}

func (s *Server) Serve() error {
	lis, err := net.Listen("tcp", fmt.Sprintf(":%d", s.port))
	if err != nil {
		return err
	}
	return s.grpc.Serve(lis)
}

func (s *Server) GracefulStop() {
	s.grpc.GracefulStop()
}
