.PHONY: build run clean

# Docker image name
IMAGE_NAME := gallery-gradle

build: Dockerfile docker-compose.yml
	docker build -t $(IMAGE_NAME) -f Dockerfile .

run: build
	docker run --rm -it -v $$(pwd)/Android/src:/workspace -w /workspace $(IMAGE_NAME) ${ARGS}

# Run gradle commands through docker
gradle:
	docker compose run --rm gradle ${ARGS}

# Clean docker resources
clean:
	docker system prune -f

# Show gradle version
gradle-version: build
	docker run --rm -v $$(pwd)/Android/src:/workspace -w /workspace $(IMAGE_NAME) --version

# Help
help:
	@echo "Available targets:"
	@echo "  build           - Build the docker image"
	@echo "  run             - Run gradlew in container (add gradle args after --)"
	@echo "  gradle          - Run gradle through docker-compose"
	@echo "  gradle-version  - Show gradle version"
	@echo "  clean           - Clean docker resources"
