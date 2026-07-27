# ── Agreemint Backend — Docker targets ──

APP_NAME := agreemint-backend
ENV_FILE := ../../envs/.agreemint.env

# Load vars from env file (make them available as Make variables)
include $(ENV_FILE)
export

# Defaults for optional vars that are rarely set in the env file. These mirror
# the application.yml fallbacks so that passing them through with -e never
# clobbers the app's built-in defaults with an empty string (an empty -e VAR=
# would win over the ${VAR:default} in the yml). Set them in the env file to
# override.
DEEPSEEK_BASE_URL        ?= https://api.deepseek.com
DEEPSEEK_MODEL           ?= deepseek-v4-pro
DEEPSEEK_TIMEOUT_SECONDS ?= 1200
R2_PRESIGN_TTL_MINUTES   ?= 5
FEATURE_PIXEL_PARITY     ?= true
RESEND_BASE_URL          ?= https://api.resend.com

# Docker network the backend attaches to so it can reach Postgres + Redis.
# The compose stack sets `name: salesiq-data` and defines a network `salesiq`
# with no explicit `name:`, so Compose creates it as "<project>_<network>" =
# "salesiq-data_salesiq". Bring that stack up first (docker compose up -d) so
# the network exists before `make run`. Override in the env file if you rename
# the compose project or give the network an explicit `name:`.
DOCKER_NETWORK ?= salesiq-data_salesiq

APP_PORT := $(PORT)

# ──────────────────────────────────────

.PHONY: build run stop logs clean restart

## Build the Docker image
build:
	docker build -t $(APP_NAME) .

## Run the container (rebuilds if image is missing)
run: build
	docker run -d --name $(APP_NAME) \
	    --network $(DOCKER_NETWORK) \
		-p $(APP_PORT):$(APP_PORT) \
		-e SPRING_DATASOURCE_URL=jdbc:postgresql://$(DB_HOST):$(DB_PORT)/$(DB_NAME) \
		-e SPRING_DATASOURCE_USERNAME=$(DB_USERNAME) \
		-e SPRING_DATASOURCE_PASSWORD=$(DB_PASSWORD) \
		-e JWT_SECRET=$(JWT_SECRET) \
		-e RESEND_API_KEY=$(RESEND_API_KEY) \
		-e RESEND_BASE_URL=$(RESEND_BASE_URL) \
		-e EMAIL_FROM=$(EMAIL_FROM) \
		-e EMAIL_FROM_NAME=$(EMAIL_FROM_NAME) \
		-e FRONTEND_BASE_URL=$(FRONTEND_BASE_URL) \
		-e CORS_ORIGINS=$(CORS_ORIGINS) \
		-e OAUTH_GOOGLE_ENABLED=$(OAUTH_GOOGLE_ENABLED) \
		-e GOOGLE_CLIENT_ID=$(GOOGLE_CLIENT_ID) \
		-e GOOGLE_CLIENT_SECRET=$(GOOGLE_CLIENT_SECRET) \
		-e OAUTH_GITHUB_ENABLED=$(OAUTH_GITHUB_ENABLED) \
		-e GITHUB_CLIENT_ID=$(GITHUB_CLIENT_ID) \
		-e GITHUB_CLIENT_SECRET=$(GITHUB_CLIENT_SECRET) \
		-e PORT=$(APP_PORT) \
		-e SPRING_PROFILES_ACTIVE=prod \
		-e REDIS_HOST=$(REDIS_HOST) \
		-e REDIS_PORT=$(REDIS_PORT) \
		-e REDIS_PASSWORD=$(REDIS_PASSWORD) \
		-e RATELIMIT_ORG_DAILY_MAX=$(RATELIMIT_ORG_DAILY_MAX) \
		-e "API_KEY_EXPIRY_WARNING_CRON=$(API_KEY_EXPIRY_WARNING_CRON)" \
		-e R2_ACCOUNT_ID=$(R2_ACCOUNT_ID) \
		-e R2_ACCESS_KEY_ID=$(R2_ACCESS_KEY_ID) \
		-e R2_SECRET_ACCESS_KEY=$(R2_SECRET_ACCESS_KEY) \
		-e R2_BUCKET_DOCUMENTS=$(R2_BUCKET_DOCUMENTS) \
		-e R2_BUCKET_PUBLIC=$(R2_BUCKET_PUBLIC) \
		-e R2_PUBLIC_BASE_URL=$(R2_PUBLIC_BASE_URL) \
		-e R2_PRESIGN_TTL_MINUTES=$(R2_PRESIGN_TTL_MINUTES) \
		-e DEEPSEEK_API_KEY=$(DEEPSEEK_API_KEY) \
		-e DEEPSEEK_BASE_URL=$(DEEPSEEK_BASE_URL) \
		-e DEEPSEEK_MODEL=$(DEEPSEEK_MODEL) \
		-e DEEPSEEK_TIMEOUT_SECONDS=$(DEEPSEEK_TIMEOUT_SECONDS) \
		-e FEATURE_PIXEL_PARITY=$(FEATURE_PIXEL_PARITY) \
		-e "AGREEMINT_STAFF_EMAILS=$(AGREEMINT_STAFF_EMAILS)" \
		$(APP_NAME)
	@echo ""
	@echo "  ✓ $(APP_NAME) running on http://localhost:$(APP_PORT)"
	@echo ""

## Stop and remove the container
stop:
	-docker stop $(APP_NAME) 2>/dev/null
	-docker rm $(APP_NAME) 2>/dev/null

## Tail container logs
logs:
	docker logs -f $(APP_NAME)

## Stop, remove container, and delete image
clean: stop
	-docker rmi $(APP_NAME) 2>/dev/null

## Restart (stop + run)
restart: stop run
