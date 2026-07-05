// Package mwebd wraps ltcmweb/mwebd (the same daemon desktop Sparrow-LTC and Cake
// Wallet use) for gomobile: the phone runs the scanner locally and the app talks
// gRPC to it on localhost, exactly like the desktop does through libmweb.
package mwebd

import "github.com/ltcmweb/mwebd"

// Server is a running mwebd instance serving gRPC on localhost.
type Server struct {
	inner *mwebd.Server
	port  int
}

// Start launches mwebd for the given chain ("mainnet"/"testnet") with its database
// in dataDir, optionally routing peer connections through a SOCKS5 proxy
// ("host:port", empty for none). Returns the localhost gRPC port via Port().
func Start(chain, dataDir, proxy string) (*Server, error) {
	server, err := mwebd.NewServer2(&mwebd.ServerArgs{
		Chain:     chain,
		DataDir:   dataDir,
		ProxyAddr: proxy,
	})
	if err != nil {
		return nil, err
	}
	port, err := server.Start(0)
	if err != nil {
		return nil, err
	}
	return &Server{inner: server, port: port}, nil
}

// Port is the localhost gRPC port the daemon is listening on.
func (s *Server) Port() int {
	return s.port
}

// Stop shuts the daemon down.
func (s *Server) Stop() {
	s.inner.Stop()
}
