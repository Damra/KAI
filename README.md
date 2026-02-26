# KAI — Kotlin AI Agent Backend

KTOR tabanlı multi-agent kod üretim sistemi. 6 uzman ajan (Planner, CodeWriter, Reviewer, Fixer, Tester, Researcher) birlikte çalışarak kod yazar, derler, test eder ve review eder.

## Mimari

```
Kullanıcı İsteği
       │
       ▼
 Meta-Controller ──→ Planner Agent (görevi alt adımlara böler)
       │
       ▼
 ┌─────┴──────┐
 │ Parallel   │
 │ Execution  │──→ Researcher / CodeWriter / Tester / Reviewer
 │ Waves      │
 └─────┬──────┘
       │
       ▼
 Verification Gate (compile → LLM-as-Judge → test) ──→ Fixer (gerekirse)
       │
       ▼
  Sonuç (REST / WebSocket stream)
```

**Core Patterns:**

| Pattern | Açıklama |
|---------|----------|
| ReAct | Think → Act → Observe döngüsü (sealed class state machine) |
| Multi-Agent | 6 uzman ajan, paralel wave execution |
| PEV | Plan → Execute → Verify (3 katmanlı doğrulama) |
| Dual Memory | pgvector (episodic) + Neo4j (semantic) |

## Hızlı Başlangıç

### Gereksinimler

- Java 21+
- (Opsiyonel) Docker — PostgreSQL + Neo4j için

### 1. Sadece API Key ile (DB'siz)

```bash
git clone https://github.com/Damra/KAI.git
cd KAI
cp .env.example .env
# .env dosyasında ANTHROPIC_API_KEY'i doldur

# Çalıştır (in-memory hafıza ile)
./gradlew run
```

### 2. Tam Kurulum (Docker ile)

```bash
# PostgreSQL (pgvector) + Neo4j başlat
cd docker
docker-compose up -d postgres neo4j
cd ..

# .env'i düzenle ve çalıştır
./gradlew run
```

## API

### Health Check

```bash
curl http://localhost:8080/api/v1/health
```

```json
{"status": "ok", "service": "KAI Agent", "version": "0.1.0"}
```

### Kod Üretme (REST)

```bash
curl -X POST http://localhost:8080/api/v1/agent \
  -H "Content-Type: application/json" \
  -d '{"message": "KTOR ile GitHub API repoları çeken client yaz"}'
```

Yanıt:
```json
{
  "answer": "...",
  "artifacts": [
    {"filename": "GitHubClient.kt", "language": "kotlin", "content": "..."}
  ],
  "planSteps": [...],
  "metadata": {
    "totalSteps": 4,
    "toolsUsed": ["kotlin_compile", "file_system"],
    "agentsInvolved": ["RESEARCHER", "CODE_WRITER", "REVIEWER"]
  }
}
```

### Streaming (WebSocket)

```javascript
const ws = new WebSocket("ws://localhost:8080/ws/agent?session=my-session");
ws.send(JSON.stringify({ message: "REST client yaz" }));
ws.onmessage = (e) => {
  const event = JSON.parse(e.data);
  // event types: thinking, tool_call, tool_result, code_generated, plan_update, done, error
  console.log(event);
};
```

## Proje Yapısı

```
src/main/kotlin/com/kai/
├── Application.kt              KTOR server + DI
├── core/
│   ├── AgentStep.kt            Sealed class state machine
│   ├── ReActAgent.kt           Think→Act→Observe döngüsü
│   ├── MetaController.kt       Multi-agent orkestrasyon
│   └── VerificationGate.kt     3 katmanlı doğrulama
├── agents/AgentFactory.kt      Ajan factory
├── tools/                      Compiler, FileSystem, WebSearch, TestRunner
├── memory/                     pgvector + Neo4j dual memory
├── llm/                        Anthropic Claude API client
├── models/                     Request/Response/StreamEvent
└── routes/                     REST + WebSocket endpoints
```

## Testler

```bash
./gradlew test
```

116 test — core models, ReAct loop, orkestrasyon, tool layer, API parsing, routes.

## Ortam Değişkenleri

| Değişken | Zorunlu | Açıklama |
|----------|---------|----------|
| `ANTHROPIC_API_KEY` | Evet | Claude API anahtarı |
| `LLM_MODEL` | Hayır | Model (varsayılan: claude-sonnet-4-5-20250929) |
| `VOYAGE_API_KEY` | Hayır | Embedding API (yoksa mock kullanılır) |
| `TAVILY_API_KEY` | Hayır | Web arama API |
| `KAI_API_KEY` | Hayır | WebSocket auth (varsayılan: dev-key) |
| `POSTGRES_URL` | Hayır | PostgreSQL bağlantısı (yoksa in-memory) |
| `NEO4J_URL` | Hayır | Neo4j bağlantısı (yoksa in-memory) |

## Teknoloji

- **Kotlin 2.0** + Coroutines
- **KTOR 2.3** (Server + Client)
- **Anthropic Claude** (tool use)
- **PostgreSQL + pgvector** (episodic memory)
- **Neo4j** (semantic memory)
- **Exposed** (SQL)
- **kotlinx.serialization** (JSON)
- **JUnit 5 + MockK** (test)
