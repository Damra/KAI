# KAI — Kotlin AI Agent Backend

KTOR tabanlı multi-agent kod üretim sistemi. 6 uzman ajan (Planner, CodeWriter, Reviewer, Fixer, Tester, Researcher) birlikte çalışarak kod yazar, derler, test eder ve review eder. Proje tanımından otomatik task kırılımı yapar, her task için kod üretir ve deploy eder.

## Ne Yapıyor?

1. **Proje tanımlıyorsun** — "E-Ticaret Platformu" gibi bir açıklama yazıyorsun
2. **AI analiz ediyor** — Projeyi Epic → Feature → Task olarak kırıyor (ör: 3 epic, 6 feature, 23 task)
3. **Otomatik geliştiriyor** — Her task için branch açıyor, kod yazıyor, review ediyor, deploy ediyor
4. **Sonuçları gösteriyor** — Üretilen kod ve review sonuçları task detayında görünüyor

Tek tıkla tüm pipeline çalışıyor: **Analyze → Plan → Execute → Review → Deploy**

## Mimari

```
Proje Tanımı (kullanıcı girdisi)
       │
       ▼
 Project Analyzer ──→ Epic/Feature/Task kırılımı (LLM ile)
       │
       ▼
 Pipeline Orchestrator
       │
       ├──→ CREATED → PLANNED (otomatik geçiş)
       ├──→ PLANNED → READY (dependency kontrolü)
       ├──→ READY → IN_PROGRESS (kod üretimi başlar)
       ├──→ IN_PROGRESS → PR_OPENED → REVIEWING (AI review)
       └──→ APPROVED → MERGED → TESTING → DEPLOYED

 Her adımda:
 ┌─────────────────┐
 │ Meta-Controller  │──→ CODE_WRITER (kod üretir)
 │ (orkestrasyon)   │──→ REVIEWER (kodu inceler)
 │                  │──→ FIXER (hata varsa düzeltir)
 └─────────────────┘
       │
       ▼
 Task Outputs DB ──→ Frontend'de görüntüleme
```

**Core Patterns:**

| Pattern | Açıklama |
|---------|----------|
| ReAct | Think → Act → Observe döngüsü (sealed class state machine) |
| Multi-Agent | 6 uzman ajan, paralel wave execution |
| PEV | Plan → Execute → Verify (3 katmanlı doğrulama) |
| Dual Memory | pgvector (episodic) + Neo4j (semantic) |
| Pipeline Automation | Analyze → Plan → Execute tek tıkla |

## Hızlı Başlangıç

### Gereksinimler

- Java 21+
- Docker + Docker Compose

### Tam Kurulum (Önerilen)

```bash
git clone https://github.com/Damra/KAI.git
cd KAI

# Tüm servisleri başlat (backend + frontend + Ollama + PostgreSQL + Neo4j)
cd docker
docker compose up -d

# Frontend: http://localhost:3000
# Backend API: http://localhost:8080
```

### Sadece Backend (DB'siz)

```bash
cp .env.example .env
# .env dosyasında ANTHROPIC_API_KEY veya Ollama ayarlarını düzenle
./gradlew run
```

## API

### Health Check

```bash
curl http://localhost:8080/api/v1/health
```

### Pipeline — Proje Oluştur ve Çalıştır

```bash
# 1. Proje oluştur
curl -X POST http://localhost:8080/api/v1/pipeline/projects \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dev-key" \
  -d '{"name":"E-Ticaret","description":"Kullanıcı kayıt, ürün listeleme, sepet, ödeme, admin paneli"}'

# 2. Tek tıkla: analiz + plan + execute
curl -X POST http://localhost:8080/api/v1/pipeline/projects/1/analyze-and-execute \
  -H "Authorization: Bearer dev-key"

# 3. Durumu kontrol et
curl http://localhost:8080/api/v1/pipeline/projects/1 \
  -H "Authorization: Bearer dev-key"

# 4. Task detayı (üretilen kod + review sonucu)
curl http://localhost:8080/api/v1/pipeline/tasks/1 \
  -H "Authorization: Bearer dev-key"
```

### Kod Üretme (Direkt)

```bash
curl -X POST http://localhost:8080/api/v1/agent \
  -H "Content-Type: application/json" \
  -d '{"message": "KTOR ile GitHub API repoları çeken client yaz"}'
```

### Streaming (WebSocket)

```javascript
const ws = new WebSocket("ws://localhost:8080/ws/agent?session=my-session");
ws.send(JSON.stringify({ message: "REST client yaz" }));
ws.onmessage = (e) => {
  const event = JSON.parse(e.data);
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
├── pipeline/
│   ├── PipelineOrchestrator.kt Analyze + execute pipeline yönetimi
│   ├── ProjectAnalyzer.kt      LLM ile proje → Epic/Feature/Task kırılımı
│   ├── TaskStore.kt            Task CRUD + output kayıt (PostgreSQL)
│   ├── PipelineTables.kt       DB tabloları (Exposed)
│   ├── TaskStateMachine.kt     Task durum geçişleri
│   └── Models.kt               Pipeline veri modelleri + DTO'lar
├── tools/                      Compiler, FileSystem, WebSearch, TestRunner
├── memory/                     pgvector + Neo4j dual memory
├── llm/                        Ollama + Anthropic Claude LLM clients
├── models/                     Request/Response/StreamEvent
└── routes/
    ├── AgentRoutes.kt          REST + WebSocket agent endpoints
    └── PipelineRoutes.kt       Pipeline API endpoints

frontend/src/
├── components/pipeline/
│   ├── PipelineView.tsx        Ana pipeline dashboard
│   ├── TaskBoard.tsx           Kanban board görünümü
│   └── TaskDetail.tsx          Task detay + outputs (kod/review)
├── hooks/usePipeline.ts        Pipeline state management
├── lib/pipeline-api.ts         Backend API client
└── types/pipeline.ts           TypeScript tipleri
```

## Docker Servisleri

| Servis | Port | Açıklama |
|--------|------|----------|
| `kai-app` | 8080 | Kotlin/Ktor backend |
| `frontend` | 3000 | React SPA (nginx) |
| `ollama` | 11434 | Lokal LLM (qwen2.5-coder) |
| `postgres` | 5432 | PostgreSQL + pgvector |
| `neo4j` | 7474, 7687 | Knowledge graph |

## Testler

```bash
./gradlew test
```

116 test — core models, ReAct loop, orkestrasyon, tool layer, API parsing, routes.

## Ortam Değişkenleri

| Değişken | Zorunlu | Açıklama |
|----------|---------|----------|
| `LLM_PROVIDER` | Hayır | `ollama` (varsayılan) veya `anthropic` |
| `OLLAMA_MODEL` | Hayır | Ollama model (varsayılan: qwen2.5-coder:3b) |
| `ANTHROPIC_API_KEY` | Hayır | Claude API anahtarı (anthropic provider için) |
| `LLM_MODEL` | Hayır | Cloud model (varsayılan: claude-sonnet-4-5-20250929) |
| `KAI_API_KEY` | Hayır | API auth key (varsayılan: dev-key) |
| `POSTGRES_URL` | Hayır | PostgreSQL bağlantısı (yoksa in-memory) |
| `NEO4J_URL` | Hayır | Neo4j bağlantısı (yoksa in-memory) |

## Teknoloji

- **Kotlin 2.0** + Coroutines
- **KTOR 2.3** (Server + Client)
- **React 19** + TailwindCSS (Frontend)
- **Ollama** (lokal LLM) + **Anthropic Claude** (cloud LLM)
- **PostgreSQL + pgvector** (episodic memory + pipeline data)
- **Neo4j** (semantic memory)
- **Exposed** (SQL ORM)
- **kotlinx.serialization** (JSON)
- **Docker Compose** (tüm servisler)
- **JUnit 5 + MockK** (test)
