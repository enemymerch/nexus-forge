# İlk deney: kanıt toplayan Analyst

> This document describes the original Analyst experiment. Model and tool connections now use
> LangChain4j and MCP; see the current [connection guide](langchain4j-mcp.md) and
> [code walkthrough](code-walkthrough.md). Development mode also includes Developer and Tester.

Bu aşamanın amacı, modelin verdiği yanıtla uygulamanın gerçekten yaptığı işi ayrı ayrı görebilmek.
Hedef PIM repository'sine yazma veya komut çalıştırma aracı eklenmedi.
Paket yapısı ve kodun baştan sona akışı için önce [kod rehberini](code-walkthrough.md) okuyabilirsin.

## Çalıştır ve döngüyü izle

Ollama yerelde açık ve `qwen3:8b` yüklüyken proje kökünden:

```powershell
.\run.ps1 -Trace
```

Başlatıcı PowerShell kodlamasını UTF-8 yapar, projeyi derler ve uygulamayı çalıştırır.
`-Trace`, `agent.trace=true` ayarını açar; Ollama'ya gönderilen JSON'u ve alınan JSON'u terminale basar.
Bu çıktıda okunan repository parçaları da bulunur. Normal kullanımda seçenek kapalıdır.
Eski PowerShell terminalinde Türkçe karakterler bozulursa UTF-8 kullan:

```powershell
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
```

Maven JVM'sinin dosya ve standart çıktı kodlaması proje içindeki `.mvn/jvm.config` dosyasında UTF-8 olarak ayarlıdır.

İlk model isteğindeki `messages` dizisinde görev talimatı ve kullanıcı görevi bulunur.
`tools`, modelin isteyebileceği araçların adlarını ve parametre şemalarını tanıtır.
Model, yalnızca bu tanımları görerek dosyalara erişemez.

`tool_calls` içindeki `function.name` ve `arguments`, modelin araç isteğidir.
`AgentRunner`, Java'da çağrı bütçesini kontrol eder; `ToolRegistry` adı ve argümanları doğrular;
`Workspace` dosyanın gerçek yolunu ve erişim kurallarını kontrol eder.

Gerçek sonuç `role: "tool"` mesajı olarak geçmişe eklenir.
Sonraki HTTP isteği bu geçmişi yeniden gönderir. Agent'ın konuşma belleği bu turda kendi `history` alanıdır.
Bu okuma modeli yeniden eğitmez; dosya parçaları istek bağlamına eklenir.

## Kanıt nasıl kontrol ediliyor?

Önceden modelden dosya yolunu, satır numarasını ve alıntıyı rapora kendisinin yazmasını istiyorduk.
Qwen bunları yanlış kopyaladığında, dosyayı okumuş olsa bile rapor reddediliyordu.
Artık Java, okunan satırlara `E1` gibi birer **kanıt numarası** veriyor. Model yalnız bu numarayı seçiyor;
dosya yolunu, satır numarasını ve birebir alıntıyı Java kendi kaydından ekliyor.

Modele gönderilen `read_file` sonucu şu biçimdedir; değerler yalnızca örnektir:

```json
{
  "path": "README.md",
  "lines": [{"id": "E1", "line": 1, "text": "# Nexus PIM"}],
  "totalLines": 100,
  "truncated": true
}
```

Bu örnekte yalnızca 1. satır modele verilmiştir. Dosyada 100 satır olması, diğer 99 satırı
kanıt olarak kullanma hakkı vermez. `list_files` çıktısı ise yalnız ad/tür bilgisidir;
dosya içeriği için kanıt numarası oluşturmaz. Boş satırlara ve hata sonuçlarına da numara verilmez.

Son aşamada araçlar kapatılır ve Ollama'dan JSON şemasına uygun bulgular istenir:

```json
{
  "findings": [{
    "claim": "Belgenin başlığında Nexus PIM yazıyor.",
    "evidence_ids": ["E1"]
  }]
}
```

`AgentRunner`, araç sonucunu `EvidenceLedger.deliver()` metoduna verir.
Bu metot boş olmayan satırlara numara ekler, numaraların kapladığı yeri de hesaba katarak çıktıyı
yeniden sınırlar ve **yalnızca son çıktıda gerçekten modele iletilen** tam satırları kaydeder.
Çağrı başına 2400, toplamda 8000 karakterlik araç bağlamı bütçesi vardır.

`AnalystReport`, `E1` numarasının bu kayıtta geçerli olup olmadığını kontrol eder.
Geçerliyse `README.md`, 1. satır ve `# Nexus PIM` metnini Java kaydından alır;
kaynak türünü de Java belirler. Model bunları yeniden yazmaz.
Uydurulmuş veya boyut sınırı yüzünden modele gönderilmemiş numaralar reddedilir.
Aynı dosya başarıyla yeniden okunursa önceki numaraları geçersiz olur; rapor son okumanın numaralarını kullanır.
Hiç kabul edilen bulgu kalmazsa uygulama hata ile biter.
Adım bütçesi kaldıysa, doğrulama hataları modele geri verilip araçsız tek düzeltme denemesi yapılır.
Bu istek de adım sınırına dahildir; yeni bir araç bütçesi açmaz.

Geçerli bir kanıt numarası, yorumun anlam bakımından doğru olduğunu kanıtlamaz. Örneğin README'de
"Kafka kullanmayı planlıyoruz" yazması Kafka entegrasyonunun tamamlandığı anlamına gelmez.
Bu yüzden Markdown bulguları **belge beyanı**, build dosyaları **çalıştırılmamış yapılandırma**,
kaynak dosyalarına dayanan yorumlar ise **davranışı test edilmemiş kaynak yorumu** olarak sunulur.
Bu ilk görev kaynak ağacını incelemeyi istemez.

## Kodu hangi sırayla okumalı?

1. `com.mcan.nexusforge.AgentApplication`: workspace, model ve endpoint ayarlarını birleştirir.
2. `agent.AgentRunner`: konuşma geçmişi, araç isteği/sonucu ve son rapor adımlarını yönetir.
3. `llm.ChatModel` ve `llm.ollama.OllamaClient`: model sözleşmesini ve yerel HTTP iletişimini sağlar.
4. `tool.ToolRegistry`, `tool.project.ProjectTools`, `workspace.Workspace`: isteği doğrular ve gerçek dosya işlemini yapar.
5. `tool.ToolOutput`, `report.EvidenceLedger`, `report.AnalystReport`: bütçe uygular, görünür satırlara kanıt numarası verir ve rapordaki numaraları denetler.

Testlerdeki sahte `ChatModel`, Ollama'ya bağlanmadan geçerli ve uydurma kanıt numaraları içeren
raporlar üretir. Bu, Java kontrol katmanının davranışını deterministik olarak sınar.
Gerçek Ollama denemesi ise modelin mevcut prompt ve araç sözleşmesiyle nasıl davrandığını gösterir;
başka bir çalışmada aynı bulguları üretmesini garanti etmez.
