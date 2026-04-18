# ── Agreemint Backend — Docker targets ──

APP_NAME := agreemint-backend
ENV_FILE := ../envs/.agreemint_env

# Load vars from env file (make them available as Make variables)
include $(ENV_FILE)
export

APP_PORT := $(PORT)

# ──────────────────────────────────────

.PHONY: build run stop logs clean restart

## Build the Docker image
build:
	docker build -t $(APP_NAME) .

## Run the container (rebuilds if image is missing)
run: build
	docker run -d --name $(APP_NAME) \
	    --network backend \
		-p $(APP_PORT):$(APP_PORT) \
		-e SPRING_DATASOURCE_URL=jdbc:postgresql://$(DB_HOST):$(DB_PORT)/$(DB_NAME) \
		-e SPRING_DATASOURCE_USERNAME=$(DB_USERNAME) \
		-e SPRING_DATASOURCE_PASSWORD=$(DB_PASSWORD) \
		-e JWT_SECRET=$(JWT_SECRET) \
		-e MAIL_HOST=$(MAIL_HOST) \
		-e MAIL_PORT=$(MAIL_PORT) \
		-e MAIL_USERNAME=$(MAIL_USERNAME) \
		-e MAIL_PASSWORD=$(MAIL_PASSWORD) \
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
		-e API_KEY_EXPIRY_WARNING_CRON=$(API_KEY_EXPIRY_WARNING_CRON) \
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
