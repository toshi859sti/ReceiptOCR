# 弥生勘定科目テーブル刷新 実装指示書
## Claude Code 向け

---

## 概要

`yayoi_accounts` テーブルを農業青色申告の実態に合わせて刷新する。
- スキーマ変更（`isEnabled`追加・`accountCode` NULL許容・`categoryC`削除・`defaultTaxCategory`追加）
- 初期データを農業用途に絞り込んで投入（e-tax農業決算書固定科目＋弥生標準科目の農業向け絞り込み）
- 補助科目4件（`parentId`で親子関係）を追加
- DBバージョン: v16 → v17

---

## 1. スキーマ変更

### 変更前（現行）

```kotlin
@Entity(tableName = "yayoi_accounts")
data class YayoiAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountName: String,
    val searchKeyAlpha: String,
    @ColumnInfo(name = "accountCode") val accountCode: String,  // UNIQUE制約あり
    val debitCredit: String,
    val categoryC: String,
    val categoryB: String,
    val categoryA: String,
    val usedForPurchase: Boolean,
    val usedForDeposit: Boolean,
    val parentId: Long? = null
)
```

### 変更後

```kotlin
@Entity(
    tableName = "yayoi_accounts",
    indices = [Index(value = ["accountCode"])]  // UNIQUEを外す（NULL許容のため）
)
data class YayoiAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountName: String,           // 勘定科目名（弥生CSV列5・11に使用）
    val searchKeyAlpha: String,        // サーチキー英字（検索UI用）
    val accountCode: String?,          // 勘定科目コード番号（NULL許容：農業追加科目はNULL）
    val debitCredit: String,           // 借貸区分: "借" or "貸"
    val categoryA: String,             // 大分類: 資産/負債/資本/収入/経費/引当金等
    val categoryB: String,             // 中分類: 現金・預金/売上債権/農業生産費 等
    // categoryC は削除
    val defaultTaxCategory: String,    // 税区分: 対象外/課対仕入10/課対仕入8/課税売上/非課税
    val usedForPurchase: Boolean,      // 購買部門の科目選択肢に表示するか
    val usedForDeposit: Boolean,       // 預金部門の科目選択肢に表示するか
    val isEnabled: Boolean = true,     // ユーザーが非表示にしたか（false=非表示）
    val parentId: Long? = null         // 補助科目の親科目ID（NULL=親科目）
)
```

---

## 2. マイグレーション（v16 → v17）

`ReceiptDatabase.kt` に以下を追加する。

```kotlin
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // yayoi_accounts を完全再作成
        // (accountCode UNIQUE制約削除・categoryC削除・defaultTaxCategory追加・isEnabled追加)
        database.execSQL("""
            CREATE TABLE yayoi_accounts_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                accountName TEXT NOT NULL,
                searchKeyAlpha TEXT NOT NULL,
                accountCode TEXT,
                debitCredit TEXT NOT NULL,
                categoryA TEXT NOT NULL,
                categoryB TEXT NOT NULL,
                defaultTaxCategory TEXT NOT NULL,
                usedForPurchase INTEGER NOT NULL DEFAULT 1,
                usedForDeposit INTEGER NOT NULL DEFAULT 0,
                isEnabled INTEGER NOT NULL DEFAULT 1,
                parentId INTEGER
            )
        """)
        // 旧データを移行（categoryC は捨てる・defaultTaxCategoryは仮で空文字）
        database.execSQL("""
            INSERT INTO yayoi_accounts_new (
                id, accountName, searchKeyAlpha, accountCode,
                debitCredit, categoryA, categoryB,
                defaultTaxCategory, usedForPurchase, usedForDeposit,
                isEnabled, parentId
            )
            SELECT
                id, accountName, searchKeyAlpha, accountCode,
                debitCredit, categoryA, categoryB,
                '' , usedForPurchase, usedForDeposit,
                1, parentId
            FROM yayoi_accounts
        """)
        database.execSQL("DROP TABLE yayoi_accounts")
        database.execSQL("ALTER TABLE yayoi_accounts_new RENAME TO yayoi_accounts")
        database.execSQL("CREATE INDEX index_yayoi_accounts_accountCode ON yayoi_accounts (accountCode)")

        // 旧データを全削除して初期データを再投入
        database.execSQL("DELETE FROM yayoi_accounts")

        // --- 初期データ投入 ---
        // INSERT文は下記「3. 初期データ」セクションのSQL群をここに展開する
        insertYayoiAccountsInitialData(database)
    }
}

private fun insertYayoiAccountsInitialData(db: SupportSQLiteDatabase) {
    // 親科目を先に挿入してIDを確定させる必要があるため、
    // 補助科目は親科目挿入後に parentId を指定して挿入する。
    // 以下のINSERT順序を厳守すること。

    // ヘルパー関数（マイグレーション内ローカル関数として定義）
    fun ins(
        accountName: String, searchKeyAlpha: String, accountCode: String?,
        debitCredit: String, categoryA: String, categoryB: String,
        defaultTaxCategory: String,
        usedForPurchase: Boolean, usedForDeposit: Boolean,
        parentId: Long? = null
    ) {
        db.execSQL("""
            INSERT INTO yayoi_accounts
            (accountName, searchKeyAlpha, accountCode, debitCredit,
             categoryA, categoryB, defaultTaxCategory,
             usedForPurchase, usedForDeposit, isEnabled, parentId)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
        """, arrayOf(
            accountName, searchKeyAlpha, accountCode,
            debitCredit, categoryA, categoryB, defaultTaxCategory,
            if (usedForPurchase) 1 else 0,
            if (usedForDeposit) 1 else 0,
            parentId
        ))
    }

    // ========== 資産 ==========

    // 現金・預金
    ins("現金",         "GENKIN",    "100", "借", "資産", "現金・預金",   "対象外", false, true)
    ins("普通預金",     "FUTSUUYO",  "111", "借", "資産", "現金・預金",   "対象外", false, true)
    ins("当座預金",     "TOUZAYO",   "110", "借", "資産", "現金・預金",   "対象外", false, true)
    ins("定期預金",     "TEIKIYO",   "113", "借", "資産", "現金・預金",   "対象外", false, true)
    // 普通預金の補助科目（parentIdは普通預金のIDを後で解決 → 後段で挿入）

    // 売上債権
    ins("売掛金",       "URIKAKE",   "130", "借", "資産", "売上債権",     "対象外", false, true)
    // 売掛金の補助科目（後段で挿入）

    // 農業棚卸資産（e-tax貸借対照表固定欄）
    ins("農産物等",         "",  null, "借", "資産", "農業棚卸資産", "対象外", false, false)
    ins("未収穫農産物等",   "",  null, "借", "資産", "農業棚卸資産", "対象外", false, false)
    ins("肥料その他の貯蔵品", "", null, "借", "資産", "農業棚卸資産", "対象外", false, false)

    // その他流動資産
    ins("前払金",       "MAEBARAI",  "160", "借", "資産", "その他流動資産", "対象外", false, false)
    ins("未収金",       "MISHUUKI",  "164", "借", "資産", "その他流動資産", "対象外", false, false)

    // 固定資産（e-tax貸借対照表固定欄）
    ins("建物・構築物", "TATEMONO",  "200", "借", "資産", "固定資産",     "課対仕入10", false, false)
    ins("農機具等",     "KIKAISO",   "203", "借", "資産", "固定資産",     "課対仕入10", false, false)
    ins("果樹・牛馬等", "",          null,  "借", "資産", "固定資産",     "対象外",     false, false)
    ins("土地",         "TOCHI",     "210", "借", "資産", "固定資産",     "対象外",     false, false)

    // 事業主貸
    ins("事業主貸",     "JIGYOU",    "291", "借", "資産", "事業主貸",     "対象外", false, false)

    // ========== 負債 ==========

    ins("買掛金",       "KAIKAKE",   "301", "貸", "負債", "仕入債務",     "対象外", false, true)
    ins("借入金",       "KARIIREK",  "320", "貸", "負債", "その他負債",   "対象外", false, true)
    ins("未払金",       "MIHARAIK",  "322", "貸", "負債", "その他負債",   "対象外", false, true)
    ins("前受金",       "MAEUKEKI",  "324", "貸", "負債", "その他負債",   "対象外", false, false)
    ins("預り金",       "AZUKARIK",  "325", "貸", "負債", "その他負債",   "対象外", false, false)
    ins("事業主借",     "JIGYOU",    "390", "貸", "負債", "事業主借",     "対象外", false, false)

    // ========== 資本 ==========

    ins("元入金",       "MOTOIRE",   "400", "貸", "資本", "資本",         "対象外", false, false)
    ins("専従者給与",   "SENJUU",    "810", "借", "資本", "資本",         "対象外", false, false)

    // ========== 収入 ==========

    ins("売上高",       "URIAGE",    "500", "貸", "収入", "農産物売上",   "課税売上", false, true)
    // 売上高の補助科目（後段で挿入）
    ins("家事消費等",   "KAJISHOU",  "583", "貸", "収入", "農産物売上",   "課税売上", false, false)
    ins("雑収入",       "ZATSUSHU",  "590", "貸", "収入", "その他収入",   "課税売上", false, false)

    // ========== 経費：農業生産費（e-tax固定欄⑧〜㉒）==========

    ins("租税公課",     "SOZEI",     "700", "借", "経費", "農業生産費",   "対象外",     true,  false)
    ins("種苗費",       "",          null,  "借", "経費", "農業生産費",   "課対仕入10", true,  false)
    ins("素畜費",       "",          null,  "借", "経費", "農業生産費",   "課対仕入10", true,  false)
    ins("肥料費",       "",          null,  "借", "経費", "農業生産費",   "課対仕入10", true,  false)
    ins("飼料費",       "",          null,  "借", "経費", "農業生産費",   "課対仕入8",  true,  false)
    ins("農具費",       "",          null,  "借", "経費", "農業生産費",   "課対仕入10", true,  false)
    ins("農薬衛生費",   "",          null,  "借", "経費", "農業生産費",   "課対仕入10", true,  false)
    ins("諸材料費",     "",          null,  "借", "経費", "農業生産費",   "課対仕入10", true,  false)
    ins("修繕費",       "SHUUZEN",   "709", "借", "経費", "農業生産費",   "課対仕入10", true,  false)
    ins("動力光熱費",   "",          null,  "借", "経費", "農業生産費",   "課対仕入10", true,  false)
    ins("作業用衣料費", "",          null,  "借", "経費", "農業生産費",   "課対仕入10", true,  false)
    ins("農業共済掛金", "",          null,  "借", "経費", "農業生産費",   "非課税",     true,  false)
    ins("減価償却費",   "GENKASHO",  "712", "借", "経費", "農業生産費",   "対象外",     true,  false)
    ins("荷造運賃手数料", "NIZUKURI","701", "借", "経費", "農業生産費",   "課対仕入10", true,  false)
    ins("雇人費",       "KYUURYOU",  "715", "借", "経費", "農業生産費",   "対象外",     true,  false)

    // ========== 経費：一般経費 ==========

    ins("地代・賃借料", "CHIDAI",    "723", "借", "経費", "一般経費",     "課対仕入10", true,  false)
    ins("利子割引料",   "RISHIWAR",  "722", "借", "経費", "一般経費",     "非課税",     true,  false)
    ins("外注工賃",     "GAICHUU",   "720", "借", "経費", "一般経費",     "課対仕入10", true,  false)
    ins("損害保険料",   "SONGAIHO",  "708", "借", "経費", "一般経費",     "非課税",     true,  false)
    ins("車両費",       "SHARYOU",   "726", "借", "経費", "一般経費",     "課対仕入10", true,  false)
    ins("消耗品費",     "SHOUMOU",   "710", "借", "経費", "一般経費",     "課対仕入10", true,  false)
    ins("支払手数料",   "SHIHARAI",  "725", "借", "経費", "一般経費",     "課対仕入10", true,  false)
    ins("水道光熱費",   "SUIDOU",    "703", "借", "経費", "一般経費",     "課対仕入10", false, false)
    ins("通信費",       "TSUUSHIN",  "705", "借", "経費", "一般経費",     "課対仕入10", false, false)
    ins("雑費",         "ZAPPI",     "760", "借", "経費", "一般経費",     "課対仕入10", true,  false)

    // ========== 引当金等 ==========

    ins("貸倒引当金戻入", "KASHIDAO", "800", "貸", "引当金等", "引当金等", "対象外", false, false)
    ins("貸倒引当金繰入", "KASHIDAO", "811", "借", "引当金等", "引当金等", "対象外", false, false)

    // ========== 補助科目（parentIdを名前で解決） ==========
    // ※ SQLiteのlast_insert_rowidは使えないため、SELECTでIDを取得してから挿入する

    db.execSQL("""
        INSERT INTO yayoi_accounts
        (accountName, searchKeyAlpha, accountCode, debitCredit,
         categoryA, categoryB, defaultTaxCategory,
         usedForPurchase, usedForDeposit, isEnabled, parentId)
        SELECT
            'JA島原雲仙', '', NULL, '借',
            '資産', '現金・預金', '対象外',
            0, 1, 1,
            id
        FROM yayoi_accounts WHERE accountName = '普通預金' AND parentId IS NULL
        LIMIT 1
    """)

    db.execSQL("""
        INSERT INTO yayoi_accounts
        (accountName, searchKeyAlpha, accountCode, debitCredit,
         categoryA, categoryB, defaultTaxCategory,
         usedForPurchase, usedForDeposit, isEnabled, parentId)
        SELECT
            '直売所', '', NULL, '借',
            '資産', '売上債権', '対象外',
            0, 1, 1,
            id
        FROM yayoi_accounts WHERE accountName = '売掛金' AND parentId IS NULL
        LIMIT 1
    """)

    db.execSQL("""
        INSERT INTO yayoi_accounts
        (accountName, searchKeyAlpha, accountCode, debitCredit,
         categoryA, categoryB, defaultTaxCategory,
         usedForPurchase, usedForDeposit, isEnabled, parentId)
        SELECT
            '直売所', '', NULL, '貸',
            '収入', '農産物売上', '課税売上',
            0, 1, 1,
            id
        FROM yayoi_accounts WHERE accountName = '売上高' AND parentId IS NULL
        LIMIT 1
    """)

    db.execSQL("""
        INSERT INTO yayoi_accounts
        (accountName, searchKeyAlpha, accountCode, debitCredit,
         categoryA, categoryB, defaultTaxCategory,
         usedForPurchase, usedForDeposit, isEnabled, parentId)
        SELECT
            '農協', '', NULL, '貸',
            '収入', '農産物売上', '課税売上',
            0, 1, 1,
            id
        FROM yayoi_accounts WHERE accountName = '売上高' AND parentId IS NULL
        LIMIT 1
    """)
}
```

---

## 3. DAOの更新

`YayoiAccountDao.kt` に以下のクエリを追加・更新する。

```kotlin
@Dao
interface YayoiAccountDao {

    // 親科目のみ取得（UI階層表示用）
    @Query("SELECT * FROM yayoi_accounts WHERE parentId IS NULL AND isEnabled = 1 ORDER BY categoryA, categoryB, accountCode")
    fun getParentAccounts(): Flow<List<YayoiAccount>>

    // 指定親科目の補助科目取得
    @Query("SELECT * FROM yayoi_accounts WHERE parentId = :parentId AND isEnabled = 1")
    fun getSubAccounts(parentId: Long): Flow<List<YayoiAccount>>

    // 購買部門用（usedForPurchase=true・親科目のみ）
    @Query("SELECT * FROM yayoi_accounts WHERE usedForPurchase = 1 AND isEnabled = 1 AND parentId IS NULL ORDER BY categoryA, categoryB")
    fun getPurchaseAccounts(): Flow<List<YayoiAccount>>

    // 預金部門用（usedForDeposit=true・親科目＋補助科目）
    @Query("SELECT * FROM yayoi_accounts WHERE usedForDeposit = 1 AND isEnabled = 1 ORDER BY parentId NULLS FIRST, accountCode")
    fun getDepositAccounts(): Flow<List<YayoiAccount>>

    // isEnabled の切り替え
    @Query("UPDATE yayoi_accounts SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    // 全件取得（設定画面用・非表示含む）
    @Query("SELECT * FROM yayoi_accounts WHERE parentId IS NULL ORDER BY categoryA, categoryB, accountCode")
    fun getAllParentAccounts(): Flow<List<YayoiAccount>>

    @Upsert
    suspend fun upsert(account: YayoiAccount)

    @Delete
    suspend fun delete(account: YayoiAccount)
}
```

---

## 4. 弥生CSV出力での税区分変換

`defaultTaxCategory` の値から弥生CSV用の税区分文字列へ変換する関数を
`util/YayoiCsvExporter.kt`（既存または新規）に追加する。

```kotlin
fun toYayoiTaxString(defaultTaxCategory: String, isDebitSide: Boolean): String {
    return when (defaultTaxCategory) {
        "対象外"     -> "対象外"
        "非課税"     -> if (isDebitSide) "非課仕入" else "非課売上"
        "課対仕入10" -> if (isDebitSide) "課対仕入込10%" else "課税売上込二10%"
        "課対仕入8"  -> if (isDebitSide) "課対仕入込 軽減8%" else "課税売上込二 軽減8%"
        "課税売上"   -> if (isDebitSide) "課対仕入込10%" else "課税売上込二10%"
        else         -> "対象外"
    }
}
```

---

## 5. `@Database` アノテーションの更新

`ReceiptDatabase.kt` のバージョンとマイグレーションリストを更新する。

```kotlin
@Database(
    entities = [
        // ... 既存エンティティ ...
        YayoiAccount::class,
        // ...
    ],
    version = 17  // 16 → 17
)
abstract class ReceiptDatabase : RoomDatabase() {
    // ...
    companion object {
        // ...
        val MIGRATION_16_17 = MIGRATION_16_17  // 上記で定義したものを追加
        
        fun buildDatabase(context: Context): ReceiptDatabase {
            return Room.databaseBuilder(...)
                .addMigrations(
                    // 既存マイグレーション...,
                    MIGRATION_16_17
                )
                .build()
        }
    }
}
```

---

## 6. 注意事項

### accountCode の重複について
弥生の元データに `KASHIDAO`（コード800・811）、`JIGYOU`（コード291・390）など
同一サーチキーで複数科目が存在する。`accountCode`のUNIQUE制約は既に外しているため問題なし。
ただし `accountName` で同定する処理（補助科目のparentId解決など）は `LIMIT 1` を必ず付けること。

### 農業専用科目のaccountCode
種苗費・肥料費・農薬衛生費・農具費・諸材料費・飼料費・素畜費・作業用衣料費・
農業共済掛金・動力光熱費・荷造運賃手数料・雇人費・果樹牛馬等・農産物等は
弥生の標準コードが存在しないためNULL。弥生CSV出力時は `accountName` のみ使用される。
弥生側にあらかじめ同名の科目が登録されている必要がある。

### 既存データの取り扱い
マイグレーションで旧データを全削除して初期データを再投入する。
旧テーブルに手動で追加した科目（肥料費・動力光熱費・農業共済掛金）は
初期データに正しい内容で含まれるため問題なし。

### fallbackToDestructiveMigration
開発中は有効のまま可。ただし本番リリース前に削除すること（known-issues.md記載済み）。

---

## 完了確認チェックリスト

- [ ] `YayoiAccount` エンティティのスキーマが上記と一致している
- [ ] `MIGRATION_16_17` が `ReceiptDatabase.kt` に追加されている
- [ ] `@Database version = 17` に更新されている
- [ ] `YayoiAccountDao` に新クエリが追加されている
- [ ] `toYayoiTaxString()` 関数が実装されている
- [ ] ビルドが通ること（`./gradlew assembleDebug`）
- [ ] 実機で起動してマイグレーションエラーが出ないこと
- [ ] 設定画面の勘定科目一覧に階層表示で科目が表示されること
- [ ] 補助科目（JA島原雲仙・直売所×2・農協）が親科目の下に表示されること
