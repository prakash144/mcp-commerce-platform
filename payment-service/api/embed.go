package api

import (
	_ "embed"
)

//go:embed payment.swagger.json
var SwaggerJSON []byte

//go:embed index.html
var IndexHTML []byte
