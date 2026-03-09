test:
	mvn test

docker-build:
	docker build -t ai-receptionist .

docker-app:
	docker run --name ai-receptionist \
		-p 8080:8080 \
		ai-receptionist

docker-postgres:
	docker run --name ai-receptionist-postgres\
		-e POSTGRES_PASSWORD=postgres \
		-e POSTGRES_USER=postgres \
		-e POSTGRES_DB=ai-receptionist \
		-p 5432:5432 \
		-d postgres:latest
