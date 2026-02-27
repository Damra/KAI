# Changelog

Projede yapilan degisikliklerin kronolojik kaydi.

---

## 2026-02-27 — Pipeline Otomasyon + Task Output Takibi

Bu guncelleme ile KAI'nin pipeline sistemi uçtan uca otomatik hale getirildi. Daha onceki surumde analiz sonrasi task'lar CREATED olarak kalip, execute PLANNED olanlari ariyordu — arada gecis yoktu. Ayrica uretilen kod ve review sonuclari kaydedilmiyordu.

### Yeni Ozellikler

#### Tek Tikla Pipeline (Analyze + Plan + Execute)
- `analyzeAndExecute()` metodu eklendi — tek bir API cagriyla tum pipeline baslatilir
- Analiz sonrasi CREATED task'lar otomatik olarak PLANNED'a geciriliyor
- PLANNED task'lar dependency kontrolunden gecip READY olanlari execute ediliyor
- Frontend'de "Analyze" butonu artik tum pipeline'i tetikliyor

#### Task Output Kaydi
- `TaskOutputs` tablosu eklendi (output_type, content, agent, created_at)
- Kod uretimi sonrasi `CODE_GENERATION` output'u kaydediliyor
- Review sonrasi `REVIEW` output'u kaydediliyor
- Task detail endpoint'i artik output'lari da donuyor

#### Task Output Gosterimi (Frontend)
- TaskDetail bilesenine "Outputs" bolumu eklendi
- Her output icin collapsible kart: baslik, agent ismi, tarih
- Uzun icerik expand/collapse ile gosteriliyor
- Monospace/code block formatinda kod gosterimi

### Bug Fixler

#### LLM JSON Parse Hatalari
- **Sorun:** qwen2.5-coder:3b modeli JSON formatini tutturamiyordu — `acceptanceCriteria` array donuyordu, trailing comma'lar vardi, bazi alanlar eksik geliyordu
- **Cozum:**
  - `FlexibleStringSerializer` eklendi — hem string hem array kabul ediyor
  - `allowTrailingComma = true` JSON parser'a eklendi
  - `tasks`, `features`, `description` alanlarina default degerler verildi
  - Prompt'lar Ingilizce'ye cevrildi (kucuk modeller Ingilizce'de daha iyi JSON uretiyor)
  - Retry mekanizmasi eklendi (3 deneme, her denemede daha sert prompt)

#### Kesilmis Pipeline Sonrasi Devam
- **Sorun:** Container restart sonrasi READY statüsündeki task'lar tekrar islenmiyordu
- **Cozum:** `executeNextTasks` artik hem PLANNED-ready hem de zaten READY olan task'lari topluyor

#### Docker Build Optimizasyonu
- `.dockerignore` dosyalari eklendi (root + frontend)
- Frontend build context'i 7.8MB'dan 2KB'a dustu

### Degisen Dosyalar

| Dosya | Degisiklik |
|---|---|
| `PipelineOrchestrator.kt` | `analyzeAndExecute()`, CREATED→PLANNED gecisi, READY task pickup, output kaydi |
| `ProjectAnalyzer.kt` | Retry mekanizmasi, Ingilizce prompt, tolerant parser |
| `PipelineTables.kt` | `TaskOutputs` tablosu |
| `Models.kt` | `TaskOutput`, `TaskOutputResponse`, `FlexibleStringSerializer` |
| `TaskStore.kt` | `saveTaskOutput()`, `getTaskOutputs()`, initialize guncellendi |
| `PipelineRoutes.kt` | `/analyze-and-execute` endpoint, task detail'de outputs |
| `pipeline-api.ts` | `analyzeAndExecute()` fonksiyonu |
| `usePipeline.ts` | Analyze butonu `analyzeAndExecute` cagiriyor |
| `pipeline.ts` | `TaskOutput` interface, `TaskDetail.outputs` |
| `TaskDetail.tsx` | Collapsible output kartlari |
| `.dockerignore` | Root + frontend dockerignore dosyalari |

### Commitler

```
0d32770 fix: execute tasks already in READY status from interrupted runs
2f13050 fix: make LLM analysis parser tolerant of malformed JSON
ef8f831 fix: improve LLM analysis parsing with retry, flexible serializer, and English prompts
40cff51 chore: add .dockerignore files to reduce build context size
47bb2aa feat: add pipeline automation (analyze+execute) and task output tracking
```

### Test Sonucu

Gercek bir proje (E-Ticaret Platformu) ile test edildi:
- LLM analizi basarili: **3 epic, 6 feature, 23 task** olusturuldu
- Task'lar dependency sirasina gore execute edildi
- Kod uretimi ve review yapildi
- 25 task'in 19'u basariyla DEPLOYED'a ulasti (kalan 6'si dependency zincirinde bekliyor)
