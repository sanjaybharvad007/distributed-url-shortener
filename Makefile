.DEFAULT_GOAL := help
SHELL := /bin/bash

API_DIR        := services/shortener-api
REDIRECTOR_DIR := services/redirector
COMPOSE        := docker compose

.PHONY: help up down logs build test test-java test-go lint lint-java lint-go lint-tf smoke load-test clean

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

up: ## Build images and start the full local stack
	$(COMPOSE) up --build -d
	@echo "API:        http://localhost:8080/swagger-ui.html"
	@echo "Redirector: http://localhost:8081"
	@echo "Prometheus: http://localhost:9090"
	@echo "Grafana:    http://localhost:3000 (admin/admin)"

down: ## Stop the local stack and remove volumes
	$(COMPOSE) down -v

logs: ## Tail service logs
	$(COMPOSE) logs -f shortener-api redirector

build: ## Build both services
	cd $(API_DIR) && mvn -B -q -DskipTests package
	cd $(REDIRECTOR_DIR) && go build ./...

test: test-java test-go ## Run all tests

test-java: ## Java unit + integration tests (Testcontainers needs Docker)
	cd $(API_DIR) && mvn -B verify

test-go: ## Go tests with race detector and coverage
	cd $(REDIRECTOR_DIR) && go test -race -coverprofile=coverage.out ./... && go tool cover -func=coverage.out | tail -1

lint: lint-java lint-go lint-tf ## Run all linters

lint-java: ## Spotless check for Java
	cd $(API_DIR) && mvn -B -q spotless:check

lint-go: ## go vet + golangci-lint
	cd $(REDIRECTOR_DIR) && go vet ./... && (command -v golangci-lint >/dev/null && golangci-lint run ./... || echo "golangci-lint not installed, skipping")

lint-tf: ## terraform fmt + validate
	cd infra/terraform && terraform fmt -check -recursive && terraform init -backend=false -input=false >/dev/null && terraform validate

smoke: ## End-to-end smoke test against the local stack
	./scripts/smoke.sh

load-test: ## Run the k6 redirect load test against the local stack
	docker run --rm -i --network host -e BASE_URL=http://localhost:8081 -e API_URL=http://localhost:8080 grafana/k6 run - < loadtest/k6/redirect.js

clean: ## Remove build artifacts
	cd $(API_DIR) && mvn -q clean || true
	cd $(REDIRECTOR_DIR) && rm -rf bin coverage.out
