# AI-Powered Smart Library Management System (Backend)

This is the backend repository for the AI-Powered Library Management System—a Spring Boot application designed to manage cataloging, membership subscriptions, book checkouts, reservations, automated fine systems, and intelligent AI features like librarian Chatbot (It gives Supports like technical issue, Gives Details about Person's Acc. Detail), book recommendation matching, and review summarization.

---

## Library System Design Architecture

Below is the complete system design architecture detailing the data flow, layer separation, caching system, AI orchestration, and database persistence layers.

![Library System Design Architecture](Library%20System%20Design%20Architecture.png)

> [!NOTE]  
> If you need to view more detailed diagrams, you can refer to the shared Google Drive folder:  
> [Detailed Diagrams Drive Folder](https://drive.google.com/file/d/1XWVQm_dzpBmv-k4lO3vLzZAhsFZWlGHe/view?usp=drive_link)

---

## Core System Architecture & Layers

The system is built on top of a classic layered architecture using Spring Boot, enhanced with event-driven notifications, distributed scheduling synchronization, and custom caching strategies to protect user context.

### 1. Spring Security & OAuth2 Layer
- **JWT Provider**: Validates session integrity. Every state-modifying request requires a signed token containing user identity and role scopes.
- **Google OAuth2 Client**: Integrated directly with Spring Security. First-time users are auto-registered, and existing local accounts can be linked to Google credentials. Profile photos are saved directly from Google identity payloads.

### 2. REST Controller Layer
- Consists of isolated REST controllers exposing clear endpoints. Controllers are strictly responsible for payload validation (using JSR-380 annotations), request mapping, and routing to service layers.

### 3. Core Business Services
- Handles transactions, validation guards, and domain rules.
- **Guard Validation on Checkout**: Before checking out a book, the `BookLoanService` checks if the user has an active membership, verifies active checkouts count against membership limits, block loans if the user has unpaid fines (damage, loss, or overdue), and ensures the user doesn't have an outstanding overdue book.
- **Queue Promotion**: When a book is returned, the system updates available copies and invokes the `ReservationQueueService` to automatically check the queue. The next user in line is promoted, the reservation state changes to `AVAILABLE`, and a system notification is created.

### 4. Custom Caching Layer (AOP-Safe)
- Uses Spring Cache abstraction backed by a local Redis container on port `6380`.
- **AOP Proxy Limit Bypass**: A dedicated `BookCacheService` handles cache reading and writing. This avoids the standard Spring self-invocation issue where internal calls bypass AOP proxies.
- **User-Context Isolation**: Since book details endpoints display context-specific flags like `alreadyHaveLoan` or `alreadyHaveReservation`, caching the raw database payload directly would leak user states. The system retrieves clean catalog details from Redis, creates a cloned instance via AOP-safe utilities, and enriches it dynamically with user-specific query flags in memory.
- **Custom Serialization (JSR-310)**: Default Redis JSON serializers fail on Java 8 date/time classes like `LocalDate` and `LocalDateTime`. We configured a custom `ObjectMapper` registered with `JavaTimeModule` (with `WRITE_DATES_AS_TIMESTAMPS` disabled) and activated polymorphic default typing to keep the cache layer stable and clean.

### 5. Event-Driven Messaging
- Decatur service dependencies using Spring's local `ApplicationEventPublisher`.
- When a payment succeeds, `PaymentService` publishes a `PaymentSuccessEvent`. The `PaymentEventListener` listens asynchronously, updating fine records to `PAID` or activating user subscriptions depending on the transaction type.

### 6. AI Orchestration Engine (Spring AI)
- **Review Summarization**: Generates summaries of reviews for specific books. Cached using Redis with a strict 5-minute TTL to keep external LLM call overheads low.
- **Conversational RAG Librarian**: An intelligent assistant answering user queries about authors, genres, or availability. It uses Spring AI's chat clients, binds logs/contexts via advisors, and queries database catalogs dynamically using custom function tools.
- **JDBC Chat Memory**: To maintain conversation history, Spring AI connects to a PostgreSQL table storing session histories across client visits.

### 7. Distributed Scheduler Locks (ShedLock)
- Since library operations run daily cleanups, subscription expirations, and overdue fine increments, running multiple server instances in production could cause duplicate scheduler execution.
- We integrated ShedLock with a Redis lock provider. Every scheduler task must acquire a lock in Redis before running, preventing concurrent executions.

---

## Tech Stack

This project contains no placeholders; every library listed below is configured and used inside the codebase:

*   **Runtime Environment**: Java 21 (LTS)
*   **Application Framework**: Spring Boot 3.4.10
*   **Build Tool**: Apache Maven (Wrapper included)
*   **Primary Database**: PostgreSQL
*   **Database Migrations & ORM**: Hibernate, Spring Data JPA
*   **Cache & Scheduler Lock Store**: Redis 7 (Alpine Docker Image)
*   **Distributed Scheduler Lock**: ShedLock (Redis Provider)
*   **AI Engine**: Spring AI (OpenAI Chat Client, JDBC Chat Memory)
*   **Security & Identity**: Spring Security, JWT, Spring OAuth2 Client
*   **Payment Integration**: Razorpay Java SDK 
*   **Notification Engine**: Spring Boot Mail (Java Mail Sender), SMTP Gmail Transport
*   **Code Boilerplate Reduction**: Lombok
*   **Containerization**: Docker & Docker Compose

---

## Database Design & Entity Mapping

The database schema is managed via Spring Data JPA and maps the following core domain structures:

1.  **`User`**: Manages credentials, roles (USER, ADMIN), provider type (LOCAL, GOOGLE), verification status, and profile info.
2.  **`Book`**: Represents the book catalog details including Dewey Decimal numbers, LC classifications, availability count, and price.
3.  **`BookLoan`**: Tracks checkout records, renewal counts, return dates, overdue status, and condition on return (RETURNED, DAMAGED, LOST).
4.  **`Reservation`**: Tracks book queue requests with state transitions (PENDING, AVAILABLE, COMPLETED, EXPIRED, CANCELLED).
5.  **`Fine`**: Tracks monetary penalties linked to loans (OVERDUE, DAMAGE, LOSS) with payment outstanding details.
6.  **`Payment`**: Records details of Razorpay transaction logs, callback state tracking, and payment intents.
7.  **`Subscription` & `SubscriptionPlan`**: Configures tiers (FREE, PREMIUM, etc.) setting limits on active checkouts and renewal periods.
8.  **`Genre`**: Implements a self-referential hierarchical mapping supporting parents and sub-genres.
9.  **`Wishlist`**: Simple user wishlists for saving book catalog items.
10. **`Notification` & `NotificationSettings`**: Manages user notifications and delivery channel permissions.
11. **`AiChatSession` & `AiChatMessage`**: Stores chat histories for Spring AI conversation persistence.

---

## Local Development Setup

Follow these steps to run the application on your local machine:

### Prerequisites
- Java 21 JDK installed.
- Maven 3.8+ installed (or use the packaged `./mvnw` binary).
- Docker Desktop installed and running.

### Step 1: Start Redis and RedisInsight
We have included a `docker-compose.yml` file in the root directory that automatically configures Redis and RedisInsight.

1. Navigate to the project root directory.
2. Run the command:
   ```bash
   docker-compose up -d
   ```
3. This will launch:
   - **Redis** on host port `6380` (to avoid conflict with standard `6379` instances on your machine).
   - **RedisInsight (UI)** on host port `8001`. It is pre-configured to connect to the local Redis container automatically without asking for credentials. Access it at `http://localhost:8001`.

### Step 2: Configure Environment Variables
You must set up the following environment variables or add them to a local configuration file before booting the app:

```bash
# Database Settings
export DB_PASSWORD=your_postgres_password

# OpenAI API Settings for Spring AI
export LLM_MODEL_API_KEY=your_openai_api_key
export LLM_MODEL_BASE_URL=https://api.openai.com/v1
export LLM_MODEL_NAME=gpt-4o-mini

# Google OAuth2 Credentials
export GOOGLE_CLIENT_ID=your_google_client_id
export GOOGLE_CLIENT_SECRET=your_google_client_secret

# Razorpay Keys
export RAZORPAY_KEY_ID=your_razorpay_key_id
export RAZORPAY_KEY_SECRET=your_razorpay_key_secret

# SMTP Mail Settings
export MAIL_USERNAME=your_gmail_address
export MAIL_PASSWORD=your_gmail_app_password
export NOTIFICATION_EMAIL_FROM=your_notification_sender_email
```

### Step 3: Run the Spring Boot Server
You can launch the backend using the Maven wrapper:

**On Windows (PowerShell):**
```powershell
.\mvnw.cmd spring-boot:run
```

**On Linux / macOS:**
```bash
chmod +x mvnw
./mvnw spring-boot:run
```

The application will start on port `8080`. You can access the API documentation via Swagger UI at:
`http://localhost:8080/swagger-ui/index.html`

---

## Contact & Developer Profile

If you want to discuss system architectures, contributions, or integrations, feel free to reach out:

*   **Developer Name**: Nikunj Kanzariya
*   **LinkedIn**: [linkedin.com/in/nikunj2006](https://www.linkedin.com/in/nikunj2006)
*   **GitHub**: [github.com/Nikunjkanzariya2006](https://github.com/Nikunjkanzariya2006)
*   **Email**: [ndkanzariya30@gmail.com](mailto:ndkanzariya30@gmail.com)
