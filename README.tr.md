# tenant-guard

*[English version](README.md)*
[![CI](https://github.com/yavuzhankursun/tenant-guard/actions/workflows/ci.yml/badge.svg)](https://github.com/yavuzhankursun/tenant-guard/actions/workflows/ci.yml) ![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)

tenant-guard, bir Spring/JDBC uygulamasının testleri sırasında kiracılar (tenant) arası veri
sızıntısını PostgreSQL'in **içinden** izleyerek bulur. Bir row-level-security "gözlemci"
politikası, executor'ın okuduğu her satırı raporlar; bir JDBC proxy'si de bu satırları testin o an
adına davrandığı kiracıyla karşılaştırır. Böylece Hibernate'in `@TenantId` filtresinin ve HTTP
seviyesindeki tarayıcıların göremediği sızıntıları yakalar: native query'ler, `JdbcTemplate`,
raporlar ve yabancı satırı hiçbir zaman "satır olarak" döndürmeyen aggregate'ler (`SUM`, `MAX`,
`count(*)`).

Durum: prototip. Yalnızca PostgreSQL. Java 21, Spring Boot 3.5, JUnit 5.

![Örnek uygulamada assertNoLeaks() çıktısı: üç ekili sızıntı, iki temiz endpoint](docs/scan-report.svg)

Tasarım kararları, verildikleri sırayla ve neyin yerine geçtikleriyle (İngilizce): [docs/design-notes.md](docs/design-notes.md).

Yazı: [Unutulan tenant filtresini veritabanının içinden yakalamak](https://medium.com/@ykurrsun/8c81babb7a4a) (Medium).

## Neden veritabanının içinden

Dışarıdan bakan bir tarayıcı yalnızca HTTP'yi görür. Diyelim ki kiracı B `GET /reports/total`
çağırdı ve `{"total": 1300}` aldı. Sorgu, kiracı filtresi unutulmuş bir
`SELECT sum(amount) FROM invoice` ise 1300 zaten A'nın faturalarını da içeriyordur. Ama yanıtta
A'ya ait tek bir alan, id ya da metin yoktur; A'nın verisiyle eşleştirilecek bir şey yoktur.
Tarayıcının yapabileceği tek şey A'nın verisi varken ve yokken yanıtları karşılaştırmaktır. Bu
karşılaştırma da değeri değişmeyen aggregate'leri kaçırır (A'nın değerleri B'nin aralığındayken
`MAX`, `EXISTS`, `count(*) > 0`).

Veritabanının içinde soru basitleşir: *bu statement hangi kiracıların satırlarını okudu?*
Politika, SQL'i kim üretmiş olursa olsun, executor'ın okuduğu her satır için bir kez çalışır.

## Nasıl çalışır

`ObserverInstaller` bir `tg_app` rolü (`NOLOGIN NOSUPERUSER NOBYPASSRLS`) ve gözlenen her
tabloda yalnızca bu role uygulanan permissive bir politika oluşturur:

```sql
CREATE POLICY tg_observer ON invoice FOR ALL TO tg_app
  USING (tg_observe('invoice', tenant_id::text)) WITH CHECK (true);
-- tg_observe(): RAISE NOTICE 'tg|%|%', tbl, row_tenant; RETURN true;
```

Politika hiçbir şeyi filtrelemez, yalnızca raporlar. `FORCE ROW LEVEL SECURITY` ayarlanmaz; bu
yüzden tablo sahibi (Flyway, seeder'lar) hiçbir zaman gözlenmez. `GuardedDataSource`
uygulamanın `DataSource`'unu sarmalar. Guard aktifken havuzdan alınan her bağlantıda
`SET ROLE tg_app; SET client_min_messages = notice` çalışır; pasifken `RESET ROLE`.

Kiracı B filtresiz bir sorgu çalıştırdığında tek bir NOTICE'in izlediği yol:

```
 Uygulama / ORM    Statement proxy'si     pgjdbc             Postgres (rol: tg_app)     TenantGuard
    |                   |                   |                        |                      |
    | executeQuery(sql) |                   |                        |                      |
    |------------------>| toplama #1 (pgjdbc warning zincirini silmek üzere)                |
    |                   |------------------>|------- sorgu --------->|                      |
    |                   |                   |                        | A-1 satırı okundu    |
    |                   |                   |                        | politika: tg_observe()|
    |                   |                   |<-- NOTICE tg|invoice|A |                      |
    |                   |                   |   (SQLWarning zinciri) |                      |
    |                   |<-- ResultSet -----|                        |                      |
    |                   | toplama #2 (execute sonrası) ----------------------------------->| RowRead(invoice, A)
    |<-- ResultSet proxy|                   |                        |                      | Observation(sql, aktör=B)
    | rs.next() ...     |  (ResultSet proxy'si: fetch/gezinme, clearWarnings ve close'da toplar) | -> Finding ACTOR_MISMATCH
    | stmt.close()      |  toplama #3 (clearWarnings/close öncesi)                         |
```

Yol üzerine notlar:

- NOTICE, `ROLLBACK` ve `READ ONLY` transaction'larda hayatta kalan tek kanaldır. Politikadan
  bir log tablosuna yazılsaydı rollback'te silinir, `READ ONLY` ise yazmayı baştan reddederdi.
- Warning zinciri bir imleçle okunur ve asla temizlenmez; uygulama kendi warning'lerini görmeye
  devam eder. Warning'leri loglayıp temizleyen bir ORM bizimkileri yutamaz: `clearWarnings` ve
  `close` çağrılmadan önce toplarız.
- Gözlemci executor'ın *okuduğu* satırları görür, veritabanından *çıkan* satırları değil.
  Postgres, leakproof koşulları (`text`/`bigint` üzerinde `=`) politikadan önce çalıştırır. Bu
  koşulların elediği satırlar hiç gözlenmez. `WHERE tenant_id = 'B'` bu yüzden sessiz kalır ve
  `findById(A'nın id'si)` tam olarak bir satırın, A'nınkinin, okunması olarak raporlanır.

## Modlar

| Mod | Ne ister | Ne yakalar | Ne kaçırır |
|---|---|---|---|
| **Aktif** | test aktörü söyler: `guard.runAs("B", ...)` | B adına okunan başka bir kiracıya ait her satır; tek satırlık `findById` IDOR'u dahil (`ACTOR_MISMATCH`) | leakproof koşulların politikadan önce elediği satırlar |
| **Pasif** | hiçbir şey (aktör bilinmez) | tek statement'ta birden fazla kiracının satırı okundu (`MULTI_TENANT_STATEMENT`) | `findById` IDOR'u (tek satır, tek kiracı). JOIN'lerde ve meşru çok kiracılı sorgularda (login, admin, zamanlanmış işler) gürültülü |
| **Diferansiyel** | yalnızca B'nin verisiyle baseline, sonra A seed edilir, tekrar çağrılır ve fark alınır (`DifferentialProbe`) | **LEAK** = A'nın verisi var diye B'nin yanıtı değişti (kesin). **SUSPECT** = yanıt değişmedi ama gözlemci A'nın satırlarını gördü | uç değerli seed ister (aşağıda). Boolean'larda (`EXISTS`, `count(*) > 0`) ve cache'lenmiş yanıtlarda kör |

Her gözlemci bulgusu tek başına SUSPECT seviyesindedir. Doğru filtrelenmiş bir JOIN bile alt
tablonun yabancı satırlarını, join onları elemeden önce okur. Diferansiyel mod, yanıt gerçekten
değiştiğinde SUSPECT'i kesin bir LEAK'e çevirir.

**Uç değerli seed.** A'nın tutarları B'nin aralığı içindeyse filtresiz bir `MAX` A'lı ve A'sız
aynı değeri döndürür ve fark hiçbir şey görmez. A da B gibi (100/200) seed edildiğinde `MAX`
yalnızca SUSPECT çıktı. `Extremes`, kurbana sıralanabilir her tipin iki ucundan değer verir:
tutar `0.01` / `999999999.99` (`numeric(12,2)`'ye sığar), zaman ve tarih `1970-01-02` /
`2999-12-31`, C, ICU ve glibc collation'larında ilk ve son sıraya düşen metinler. A'yı ikisiyle de
seed edin. O zaman `MAX`, `MIN` ve `ORDER BY ... LIMIT 1` A sızar sızmaz değişir.

## Hızlı başlangıç

```java
@SpringBootTest
@TenantIsolationTest(tenantColumn = "tenant_id")   // tablolar: search_path üzerinde bu kolona sahip her tablo
class TenantIsolationIT {

    @TestConfiguration
    static class Seed {
        @Bean
        TenantSeeder seeder(JdbcTemplate jdbc) {          // guard pasifken, tablo sahibi olarak çağrılır
            return tenant -> {
                if (tenant.equals("A")) {                    // önce kurban seed edilir: temiz başla
                    jdbc.execute("TRUNCATE invoice_line, invoice RESTART IDENTITY");
                }
                List<BigDecimal> amounts = tenant.equals("A")
                        ? Extremes.amounts()
                        : List.of(new BigDecimal("100"), new BigDecimal("200"));
                for (int i = 0; i < amounts.size(); i++) {
                    jdbc.update("INSERT INTO invoice (tenant_id, number, amount) VALUES (?, ?, ?)",
                            tenant, tenant + "-" + i, amounts.get(i));
                }
            };
        }
    }

    @Autowired InvoiceRepository invoices;

    @Test
    void kiracilarBirbiriniGoremez(TenantGuard guard, ObserverSetup setup) {
        // Uygulamanın kendi tenant bağlamını da burada B'ye ayarlayın; runAs yalnızca okumaları etiketler.
        guard.runAs("B", invoices::findAll);

        assertThat(setup.skippedTables()).isEmpty();          // atlanan tablo = gözlenmeyen tablo
        assertThat(guard.findings(Finding.Rule.ACTOR_MISMATCH)).isEmpty();
    }
}
```

Her testten önce eklenti guard'ı pasifleştirip sıfırlar, varsa `TenantSeeder` bean'ini önce
`victim` ("A") sonra `attacker` ("B") için çağırır, gözlemciyi kurar, guard'ı aktifleştirir,
self-check'i çalıştırır ve sıfırlar. Her testten sonra guard'ı pasifleştirir. Test metotları
`TenantGuard`, `ObserverSetup` ve `TenantSeeder` parametresi alabilir. Anotasyon,
`TenantGuardConfiguration`'ı (guard bean'i + `DataSource` sarmalayıcısı) meta-`@Import` ile
getirir; Spring Boot'un test desteği bunu tanır.

- Otomatik keşif, uygulama bağlantısının `search_path`'indeki her şemayı ya da `schemas = {...}`
  ile verilenleri tarar. Büyük-küçük harf karışık adlar ve reserved word'ler tırnaklanıp gözlenir;
  tırnak, `|`, `.`, boşluk ya da kontrol karakteri içeren adlar ve politikaları `tg_app`'e hiçbir
  şey vermeyen tablolar `ObserverSetup.skippedTables()` içinde gerekçesiyle atlanır. Açıkça verilen
  `tables = {...}` (`tablo` ya da `şema.tablo`) ise hata verir. Gözlenecek hiç tablo yoksa test düşer.
- Aktör, kolonun tipi ne olursa olsun (`integer`, `bigint`, `uuid`, `text`, `company_id`...)
  `tenant_kolonu::text` ile birebir karşılaştırılır: `String.valueOf(id)` ya da `UUID.toString()`
  geçin. Sayısal normalizasyon bilerek yok: `"02"` ile `"2"` farklı text kiracılardır ve yanlış
  biçimlenmiş bir aktör rastgele eşleşmek yerine yüksek sesle hata verir.
- Birden fazla `DataSource` bean'i mi var? `@Primary` olan gözlenir; diğerleri kapatılır ve
  `ObserverSetup.warnings()` içinde listelenir. `@TenantIsolationTest(dataSources = {"dataSource",
  "reporting"})` ile fazlasını gözleyin; `AbstractRoutingDataSource` her hedefinde kurulup
  self-check edilir. Guard doğrudan havuzun üstüne, `LazyConnectionDataSourceProxy` /
  `TransactionAwareDataSourceProxy`'nin altına konur (transaction-aware proxy'nin üstündeki bir guard
  ham bağlantıların transaction'dan kaçmasına yol açar); lazy proxy'nin read-only replikası da
  gözlenir.
- Seed'i `@BeforeEach` içinde mi yapmak istiyorsunuz? Eklenti callback'leri `@BeforeEach`
  metotlarından *önce* çalışır; orada guard zaten kuruludur. `guard.deactivate()` deyin, seed
  edin, sonra `guard.activate(); guard.reset()`.
- `@SpringBootTest(classes = ...)` kullanıldığında Spring Boot iç içe `@TestConfiguration`
  sınıflarını yok sayar. Seeder konfigürasyonunu `classes` listesine de ekleyin.
- `ObserverSetup.warnings()` eklentinin güvenli hale getiremediklerini listeler, ör. Spring
  scheduling'in açık olması. Kurulum hataları neyi kontrol edeceğinizi söyler (DataSource
  sarmalanmamış / yok / birden fazla, sahip olmayan kullanıcıyla kurulum, self-check hataları).

## Self-check

Bu aracın en kötü hata modu sessizce eksik raporlamadır: RLS uygulanmıyorsa sızdıran bir uygulama
da temiz bir uygulama gibi sıfır bulgu üretir. Bu yüzden her testten önce `SelfCheck` şu
durumlarda yüksek sesle FAIL verir:

- etkin rol superuser ya da `BYPASSRLS` sahibi. Testcontainers'ın varsayılan kullanıcısı
  superuser'dır; RLS'in uygulanmasını sağlayan şey `SET ROLE tg_app`'tir;
- tek satırlık `tg_probe` tablosuna atılan bir `SELECT` gözlemci NOTICE'i üretmiyor (politika
  yok, `client_min_messages` NOTICE'i filtreliyor, proxy yolda değil).

## Doğrulanmış davranış

Her satır `src/test` altında bir testtir (`ObserverScenariosTest`, `DifferentialScenarioTest`,
`JdbcPathMatrixTest`, `TenantIsolationTestAnnotationTest`), Postgres 16'ya karşı çalışır.

| Senaryo | Sonuç |
|---|---|
| `WHERE tenant_id = 'B'`, seq scan | bulgu yok |
| B olarak filtresiz `SELECT sum(amount)` | `ACTOR_MISMATCH` (A) + `MULTI_TENANT_STATEMENT` |
| `JOIN invoice ... WHERE i.tenant_id = 'B'` ile filtrelenen alt tablo | `invoice_line` için `ACTOR_MISMATCH`: **yanlış alarm**, satırlar okundu ama join eledi |
| `@Transactional(readOnly = true)` / rollback olan transaction | notice'ler korundu, bulgu üretildi |
| superuser bağlantı | self-check FAIL (`superuser=true`) |
| kendi permissive RLS politikası olan tablo | gözlemci `AS RESTRICTIVE` kurulur; uygulamanın politikası filtrelemeye devam eder, elenen satırlar hiç gözlenmez |
| politikaları `tg_app`'e hiçbir şey vermeyen tablo | reddedilir / gerekçesiyle atlanır: yalnızca restrictive politikalı tablo her satırı reddeder |
| B olarak Hibernate `findAll()` | `ACTOR_MISMATCH` (A) |
| B olarak `findById(A'nın id'si)` (aktif) | yakalandı |
| aktörsüz `findById(A'nın id'si)` (pasif) | satır gözlendi, **bulgu yok** |
| 33 JDBC erişim yolu: Statement, PreparedStatement (tek/tekrar), batch, CallableStatement, cursor (`fetchSize=1`), scrollable, JdbcTemplate, Hibernate (JPQL, native, stream, `find`), `unwrap(JDBC arayüzü)` | hepsi gözlendi |
| diferansiyel: SUM raporu, `findAll` listesi | LEAK |
| diferansiyel: filtreli liste | CLEAN |
| diferansiyel: JOIN ile filtrelenen alt tablo | SUSPECT |
| diferansiyel: `Extremes` seed'iyle `MAX`, `MIN`, `ORDER BY amount DESC LIMIT 1` | LEAK |
| diferansiyel: `EXISTS(... WHERE lower(number) = 'b-3')` | SUSPECT: fark kör, tek sinyal gözlemci |
| diferansiyel: B'nin satırları varken çıplak `EXISTS(SELECT 1 FROM invoice)` | CLEAN: tarama B'nin ilk satırında durur, A'nın satırları hiç okunmaz |


### Ne yakalıyor: sızıntı korpusu

`LeakCorpusTest` 33 sızıntı kalıbını B kiracısı olarak çalıştırır ve her satırda üç katmanı birden
doğrular (gözlemci = `ACTOR_MISMATCH`, pasif = `MULTI_TENANT_STATEMENT`, fark = `DifferentialProbe`).
`CorpusMechanismTest` her kör noktanın mekanizmasını kanıtlar (planner sırası, sahip hakları).

| Kalıp | Gözlemci | Pasif | Fark |
|---|---|---|---|
| JPQL / native / `JdbcTemplate` listeleri, `SUM`, `COUNT`, `MAX`, `MIN`, CTE, `ORDER BY ... LIMIT 1`, `Pageable`, Criteria API, `Specification`, `tenant_id` seçmeyen DTO projection, `@Transactional(readOnly)`, `@Async`, `StatelessSession`, `getResultStream()`, SQL fonksiyonu (`SECURITY INVOKER`), `WITH (security_invoker = true)` view | yakalar | yakalar | LEAK |
| `findById(kurban id)`, `IN (kurban id'leri)` | yakalar | sessiz (statement'ta tek kiracı) | LEAK |
| `findById` sonra bellekte tenant kontrolü (404) | yakalar | sessiz | SUSPECT |
| `EXISTS ... WHERE lower(name) = ?` (leakproof değil) | yakalar | yakalar | SUSPECT |
| `EXISTS ... WHERE name = ?` (leakproof), kimsede olmayan ad | **kör** | kör | CLEAN |
| aynısı, kurbanın adı sorgulanınca | yakalar | sessiz | LEAK |
| üst tablonun tenant'ı üzerinden JOIN ile filtrelenen alt satırlar (doğru kod) | yanlış alarm | yanlış alarm | CLEAN → SUSPECT |
| sızdıran sorgunun üstünde `@Cacheable` | baseline cache'i ısıtınca **kör** | kör | CLEAN; kurban seed edildikten sonra `CacheManager` temizlenirse LEAK |
| `security_invoker` olmayan view | **kör**: RLS view sahibi olarak kontrol edilir, sahip muaf | kör | LEAK |
| `SECURITY DEFINER` SQL fonksiyonu | **kör**: gövde sahip olarak çalışır | kör | LEAK |
| kontroller: `findByTenantId(B)`, `findByIdAndTenantId(kurban id, B)` | sessiz | sessiz | CLEAN |

Yani gözlemcinin dört kör noktası var: cevabı kurbana bağlı olmayan leakproof koşullar,
cache'lenmiş yanıtlar, varsayılan view'lar ve `SECURITY DEFINER` fonksiyonlar. Son ikisini fark
katmanı kapatır; üçüncüsünü aşamalar arasında cache temizlemek; ilki dardır.

Bu testler beş "sessiz eksik raporlama" hatası buldu; hepsi düzeltildi:

1. pgjdbc, her `execute` başında statement'ın warning zincirini `clearWarnings()`'e uğramadan
   siler. Tekrar kullanılan bir `PreparedStatement` önceki çalıştırmanın notice'lerini
   kaybediyordu. Düzeltme: her execute'tan önce topla.
2. `fetchSize` ayarlı ve autocommit kapalıyken sonraki cursor fetch'lerinin notice'leri
   `Statement`'a değil `ResultSet`'e düşer. İlk partiden sonraki her satır görünmüyordu.
   Düzeltme: `ResultSet`'i de proxy'le; gezinme, `clearWarnings` ve `close`'da topla.
3. `unwrap(Connection.class)` ham sürücü bağlantısını döndürüyordu; oradan açılan statement'ları
   kimse gözlemiyordu. Düzeltme: istenen tip bir JDBC arayüzüyse proxy'yi döndür.
4. Sürücü zinciri bizim görmediğimiz bir yerde temizleyince okuma imleci bayatlıyor, yeni
   notice'ler atlanıyordu. Düzeltme: zincirin baş nesnesi değişince sıfırdan say.
5. Havuzlar `executeQuery` ve `getResultSet` için ayrı wrapper üretince aynı sonuç iki kez
   sarılıyor ve fetch notice'leri çift sayılıyordu. Düzeltme: statement'ın o anki sonucu başına
   tek proxy.

## Varsayımlar ve sınırlar

- **Yalnızca PostgreSQL.** Mekanizma RLS + `RAISE NOTICE` + pgjdbc'nin `SQLWarning` zinciri.
- **Şemanın sahibi uygulama kullanıcısı.** Gözlemci, guard pasifken sarmalanmış `DataSource`
  üzerinden, yani uygulamanın kendi kimlik bilgileriyle kurulur. Bu kullanıcı tabloların sahibi
  olmalıdır (Flyway ya da uygulama kullanıcısıyla çalışan migration'lar); sahibi olmayan bir rolle
  Hibernate `ddl-auto` desteklenmez. `tg_app`'e `search_path`'teki her şema ve gözlenen tablo
  içeren her şema verilir. Tenant kolonu olmayan şema-başına-kiracı tasarımları (Hibernate `SCHEMA`
  multitenancy) kapsam dışıdır ve bunu söyleyen bir mesajla düşer.
- **Paralel test çalıştırma yok.** Aktör ve bulgular thread-local değil, süreç genelidir. Bu
  bilinçli bir karar: kiracı etiketi `@Async` ve thread pool'larda da hayatta kalır.
- **Kendi RLS politikası olan tablolara RESTRICTIVE gözlemci kurulur.** Postgres permissive
  politikaları OR'lar; her zaman true dönen permissive bir gözlemci uygulamanın izolasyonunu
  kapatırdı. Restrictive gözlemci ise AND'lenir: uygulamanın politikası önce çalışır, elediği
  satırlar hiç gözlenmez. Politikaları `tg_app`'e hiçbir şey vermeyen tablo reddedilir; yalnızca
  restrictive politika "hepsini reddet" demektir ve gözlemci hiçbir şey göremezdi.
- **Leakproof koşullar ve erken çıkışlar.** Politikadan önce text/bigint `=` ile elenen ya da hiç
  ulaşılmayan (`EXISTS`, `LIMIT`) satırlar gözlenmez.
- **View'lar ve `SECURITY DEFINER` fonksiyonlar sahibi olarak çalışır**; sahip RLS'ten muaf olduğu
  için gözlemci bunlar üzerinden yapılan okumaları görmez, fark katmanı görür.
  `WITH (security_invoker = true)` view'lar ve `SECURITY INVOKER` fonksiyonlar gözlenir.
- **Cache okumayı gizler.** Baseline sırasında ısınan bir `@Cacheable` sonucu Postgres'e bir daha
  ulaşmaz. Kurbanı seed ettikten sonra `CacheManager`'ı temizleyin.
- **`@Scheduled` işler global aktör altında çalışır.** Scheduling açıksa eklenti uyarır;
  `tenantguard.scheduling=off` hiçbir işi çalıştırmayan bir `TaskScheduler` kurar.
- **`tg|` NOTICE öneki taklit edilebilir:** uygulamanın kendi `RAISE NOTICE`'i aynı öneki
  kullanırsa yanlış bulgu üretir.
- **Okunan satır başına bir NOTICE.** Büyük seed'ler ve tam taramalar yavaştır. Bir avuç satır
  seed edin.
- **`GRANT ... ON ALL TABLES` yalnızca mevcut tabloları kapsar.** Kurulumdan sonra oluşturulan
  tablolar `tg_app`'e verilmez.
- **Sürücüye özgü `unwrap`** (ör. `PGConnection`) ham bağlantıyı döndürür; oradan açılan
  statement'lar proxy'yi atlar.
- Yazma tarafı (`WITH CHECK`) gözlenmez.

## Endpoint taraması

HTTP katmanı, uygulamanın her `GET` endpoint'ini saldırgan (B) olarak kurbanın (A) verisine
karşı çağırır; altta gözlemci ve diferansiyel sonda çalışır. Endpoint'ler Spring'in
`RequestMappingHandlerMapping` bean'inden keşfedilir: OpenAPI dosyası ve token listesi gerekmez.

```java
@SpringBootTest
@AutoConfigureMockMvc
@TenantIsolationTest(tenantColumn = "tenant_id")
class EndpointIsolationIT {

    @Test
    void kiracilarBirbiriniGoremez(TenantGuard guard, @Autowired MockMvc mockMvc,
                                   @Autowired ApplicationContext context, @Autowired DataSource dataSource) {
        EndpointScanner.builder(mockMvc, guard)
                .endpoints(EndpointDiscovery.discover(context))
                .exclude("/api/v1/tenants/**")                        // platform-admin endpoint'leri, tasarım gereği çok kiracılı
                .seeder(tenant -> /* o kiracının satırlarını sahibi olarak ekle */)
                .authenticator(tenant -> request -> { request.addHeader("X-Tenant", tenant); return request; })
                .resourceIds(ResourceIds.builder()
                        .id("/invoices/{invoiceId}/lines/{lineId}", "lineId", tenant -> /* override */)
                        .fallback(new TableGuessingResourceIds(dataSource, "tenant_id"))
                        .build())
                .build()
                .scan()
                .assertNoLeaks();
    }
}
```

Tarama aşamalarla ilerler: B'yi seed et → path değişkeni olmayan her endpoint'i B olarak çağırıp
baseline kaydet (her istekte değişen alanları ayıklamak için iki kez) → A'yı seed et → her
endpoint'i B olarak çağır, `{id}` tipi değişkenlere A'nın id'lerini koy. Kararlar:

| Karar      | Anlamı                                                                                        |
|------------|-----------------------------------------------------------------------------------------------|
| `LEAK`     | A'nın id'siyle çağrılan endpoint **veri içeren** 2xx döndü, ya da A'nın verisi eklenince yanıt değişti |
| `SUSPECT`  | Yanıt değişmedi / boş / 403 / 404, ama gözlemci çağrı sırasında A'nın satırlarının okunduğunu gördü |
| `CLEAN`    | İkisi de değil                                                                                |
| `SKIPPED`  | Id çözülemedi (gerekçe notta) ya da 401: authenticator büyük ihtimalle yanlış                 |
| `EXCLUDED` | `.exclude(antPattern)` / `.excludeIf(predicate)` ile eşleşti; hiç çağrılmaz, kuralıyla listelenir |
| `ERROR`    | 5xx ya da handler exception. `assertNoLeaks()`'i düşürür: çökmek güvenliğin kanıtı değildir  |

**Boş gövde sızıntı değildir.** Doğru filtrelenmiş bir sorgu kurbanın id'si için hiçbir şey bulmaz
ve yine de `200 []`, `{}`, `null`, `0`, `0.00` ya da `false` döner. Böyle bir 2xx, gözlemci kurbanın
satırlarını gördüyse SUSPECT, görmediyse CLEAN'dir (`200 with empty body [] for tenant A's
/invoices/3/lines`). Kontrol bilerek düzdür: `{"items": [], "total": 0}` yine veri sayılır.
Kurbanın gerçek değeri `0` ya da `false` olabiliyorsa `Extremes` ile seed edin.

Her endpoint'i hariç tutmak ya da hiçbirini çözememek `No endpoint was verified` ile düşer.

`assertNoLeaks()` örnek uygulamada şuna benzer bir tablo basar:

```
Tenant isolation scan: 3 of 5 GET endpoint(s) failed for tenant B against tenant A's data (3 LEAK)
  VERDICT  ENDPOINT           STATUS NOTE
  LEAK     GET /invoices      200    response changed after tenant A's data was added: $.size, $[2].amount, ...
  LEAK     GET /invoices/{id} 200    200 for tenant A's /invoices/3; observer: invoice rows of tenant A read by select ... where i1_0.id=?
  LEAK     GET /reports/total 200    response changed after tenant A's data was added: $.total; observer: ...
```

`{id}` → tablo eşlemesi, tabloyu önceki path parçasından ve değişken adından tahmin eder
(`/invoices/{id}` → `invoice`, `{invoiceLineId}` → `invoice_line`), her identifier'ı SQL'e
ulaşmadan doğrular ve hiçbiri eşleşmezse denediği isimlerle birlikte `SKIPPED` raporlar. İç içe
id'ler bağımsız çözülür; `/invoices/{a}/lines/{b}` genellikle override ister. Yalnızca `GET`
taranır; query parametresi, pagination ve istek gövdesi üretilmez. Guard'ın aktörü global olduğu
için çağrılar sıralıdır.

## Nasıl doğrulanıyor

- Her push'ta Testcontainers Postgres üzerinde **503 test** (GitHub Actions); aşağıdaki ortam
  matrisi `main`'e her push'ta ve her gece.
- **33 yollu JDBC erişim matrisi** (`JdbcPathMatrixTest`): aynı sızıntı Statement,
  PreparedStatement, batch, callable, cursor, JdbcTemplate, Hibernate ve `unwrap` yollarının her
  birinden.
- **33 kalıplık sızıntı korpusu** (yukarıda), her kör nokta için mekanizma testi.
- **Mutasyon testi** (`mvn -Pmutation org.pitest:pitest-maven:mutationCoverage`, JDBC çekirdeğinde
  PIT STRONGER mutator'ları): 226 mutantın %97'si öldürüldü; kalanlar eşdeğer mutant (void dönüş,
  proxy argüman şekilleri). Bozulan bir toplama yeşil kalamıyor.
- **Spring Data'nın resmi `@TenantId` örneği** Postgres'te
  ([examples/spring-data-tenantid](examples/spring-data-tenantid)): `findAll`, JPQL ve Hibernate
  6.6'da `findById` filtreleniyor, öteki kiracıdan hiçbir satır okunmuyor; örneğin native sorgusu
  filtrelenmiyor ve gözlemci bunu `person` üzerinde `ACTOR_MISMATCH` + `MULTI_TENANT_STATEMENT`
  olarak raporluyor; örneğin kendisi bu boşluğu ancak "exception bekle" ile kapatabiliyor.
- **Bir saha koşusu:** 231 GET endpoint, 98 migration ve 63 tenant tablosu olan üretimdeki bir
  Spring Boot uygulaması: 1 kesin sızıntı (tenant filtresiz bir şube-adı alt sorgusu), kod okunarak
  bulunan 1 yazma tarafı IDOR, 5 ilgisiz 500. İlk koşu 15 yanlış LEAK de üretti; aşağıdaki boş
  gövde kuralı ve endpoint hariç tutma bu koşudan çıktı ve 12'sini kaldırıyor.


### Ortam matrisi

CI her push/PR'da tam süiti varsayılanlarla koşar (Spring Boot 3.5, Boot BOM'undaki pgjdbc,
HikariCP, Postgres 16). `matrix` job'ı (`main`'e push, her gece) her seferinde tek bir boyutu
değiştirir; aşağıdaki her hücre eklenmeden önce lokalde geçti:

| Boyut | Değerler | Komut |
|---|---|---|
| Postgres | 13, 14, 15, 16, 17 | `mvn verify -Dtenantguard.pg.image=postgres:15-alpine` |
| Spring Boot | 3.2, 3.3, 3.4, 3.5 (× PG 16) | `mvn verify -Dboot.version=3.2.12` |
| pgjdbc | 42.6.2, 42.7.10, 42.7.14 (× PG 16) | `mvn verify -Dpostgresql.version=42.6.2` |
| Bağlantı havuzu | HikariCP (varsayılan), tomcat-jdbc, DBCP2, c3p0, vibur, havuzsuz; her biri statement cache'li ve cache'siz | `mvn verify -Dtenantguard.pool=c3p0 -Dtenantguard.pool.cache=true` |
| Tenant kolonu | `text`, `integer`, `bigint`, `uuid`, `company_id`, composite key parçası, `"MixedCase"`, reserved word | `ShapesTestBase` testleri |
| Şemalar | `public`, çok şemalı `search_path`, `schemas = ...` ile yol dışı tablolar, iki şemada aynı tablo adı | `SchemaShapesTest`, `SearchPathWiringTest` |
| Uygulama kablolaması | `DataSourceBuilder`, `LazyConnectionDataSourceProxy`, `TransactionAwareDataSourceProxy`, birden fazla DataSource, `AbstractRoutingDataSource`, Liquibase, sahip / yalnızca DML rolüyle `ddl-auto`, NOSUPERUSER sahip, `JpaTransactionManager` + `JdbcTemplate`, `REQUIRES_NEW` | `wiring/*Test` |
| Mutasyon testi (manuel) | PIT, STRONGER mutator'lar, JDBC çekirdeği | `mvn -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage` |

Desteklenen en eski Spring Boot: 3.2 (Spring Framework 6.1, Hibernate 6.4); Java 21. Boot 3.2'de
test fixture'ı Flyway 10 ister; `-Dboot.version=3.2.12` bunu otomatik açar.

Matrisin ilk CI koşuları macOS'ta hiç görünmeyen iki şey daha yakaladı, ikisi de havuz sırasına
bağlı: bir test Hikari bağlantısını kirli `search_path` ile, bir diğeri autocommit kapalı iade
ediyordu; Hikari ikisini de dönüşte onarır, tomcat-jdbc onarmaz ve sonraki observer kurulumu
`ALTER TABLE ... ENABLE ROW LEVEL SECURITY`'de job'ın 30 dakikası boyunca bloke oldu. Kurulumcu artık
DDL'ini `lock_timeout` altında çalıştırıyor ve zaman aşımında asılı kalmak yerine kilidi tutan
oturumları (pid, durum, son sorgu) söylüyor.

Matrisin bulup düzelttikleri: havuz **statement cache**'i açıkken (tomcat-jdbc, DBCP2, c3p0,
vibur) cache'lenmiş bir `PreparedStatement` önceki kullanıcının warning zincirini sonrakine
devrediyordu; satırlar çift sayılıyor ve A'nın kendi okuması B'ye yazılıyordu. DBCP2'nin `ResultSet`
wrapper'ı `unwrap`'ta kendini döndürüyor, bu da sonuç başına iki proxy üretiyordu. c3p0 sürücünün
kapattığı sonucu açık gösterip `getWarnings`'te fırlatıyor. Üçü de düzeltildi ve `PoolMatrixTest`
ile sabitlendi. Kalan havuz davranışları: statement cache'ler `fetchSize`'ı sonraki ödünç alana
taşır (bulgular fetch partisi başına bölünür, satır sayıları tam kalır); c3p0 aynı sonuçta
`executeQuery` sonrası `getResultSet`'i guard olsun olmasın kendi `InternalError`'ıyla reddeder.

## Yol haritası

- Gradle ve Kotlin tüketicileri.
- Açık kaynak çok kiracılı Spring projelerinde koşular.
- Kurulumda `security_invoker` olmayan view'lar ve `SECURITY DEFINER` fonksiyonlar için uyarı.
- Üretilmiş gövdelerle POST/PUT/DELETE taraması; DELETE en sona, sonrasında yeniden seed.
- Yazma tarafının gözlenmesi (başka kiracıya `INSERT`/`UPDATE`).
- Sabit `tg|` öneki yerine oturum başına NOTICE nonce'u.
- Kurulumdan sonra oluşturulan tablolar için `ALTER DEFAULT PRIVILEGES`.

## Benzer araçlar

- **AuthProbe**: dışarıdan bakar.
- **Burp Autorize**: dışarıdan bakar.
- **Snyk API & Web**: dışarıdan bakar.
- **Unit 42 BOLABuster**: dışarıdan bakar.

tenant-guard bunları içeriden tamamlar. Yanıtta hiç görünmeyen okumaları görür; Postgres'e
dokunmayan hiçbir şeyi göremez.
