package com.aster.files.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Aster Files 的 SQLite 数据库。
 *
 * 严格对应需求文档 6.3 的 7 张表：
 * meta、trash_entries、integrity_tasks、integrity_results、
 * integrity_action_jobs、audit_log、compliance_meta。
 *
 * 额外只增加不改变表结构的查询索引。数据库位于应用私有目录，
 * 外键约束在 onConfigure 中开启，integrity_results 对任务级联删除。
 */
class AsterDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        CREATE_TABLE_STATEMENTS.forEach { db.execSQL(it) }
        CREATE_INDEX_STATEMENTS.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 版本 1 是初始 schema。后续版本必须在此追加显式迁移，禁止静默删表丢数据。
        throw IllegalStateException(
            "缺少数据库迁移：$oldVersion -> $newVersion。请在 AsterDatabase 中追加显式迁移。"
        )
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException(
            "数据库版本回退不受支持：$oldVersion -> $newVersion。"
        )
    }

    /**
     * 导出当前 schema 为 JSON，供设置页展示/分享。
     *
     * 读取 sqlite_master、PRAGMA table_info 与 PRAGMA foreign_key_list，
     * 不修改任何业务数据。调用方应在 IO 线程执行（Repository 已封装）。
     */
    fun exportSchemaJson(): String {
        val db = readableDatabase
        val root = JSONObject()
        root.put("database", DATABASE_NAME)
        root.put("version", DATABASE_VERSION)

        val tableRows = ArrayList<Pair<String, String>>()
        db.rawQuery(
            "SELECT name, COALESCE(sql, '') FROM sqlite_master " +
                "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                tableRows.add(cursor.getString(0) to cursor.getString(1))
            }
        }

        val tables = JSONArray()
        for ((name, sqlText) in tableRows) {
            val table = JSONObject()
            table.put("name", name)
            table.put("sql", sqlText)

            val columns = JSONArray()
            db.rawQuery("PRAGMA table_info(" + quoteIdentifier(name) + ")", null).use { cursor ->
                while (cursor.moveToNext()) {
                    columns.put(
                        JSONObject().apply {
                            put("cid", cursor.getInt(0))
                            put("name", cursor.getString(1))
                            put("type", cursor.getString(2) ?: "")
                            put("notNull", cursor.getInt(3) != 0)
                            put(
                                "defaultValue",
                                if (cursor.isNull(4)) JSONObject.NULL else cursor.getString(4)
                            )
                            put("primaryKey", cursor.getInt(5))
                        }
                    )
                }
            }
            table.put("columns", columns)

            val foreignKeys = JSONArray()
            db.rawQuery(
                "PRAGMA foreign_key_list(" + quoteIdentifier(name) + ")",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    foreignKeys.put(
                        JSONObject().apply {
                            put("id", cursor.getInt(0))
                            put("seq", cursor.getInt(1))
                            put("table", cursor.getString(2))
                            put("from", cursor.getString(3))
                            put("to", cursor.getString(4))
                            put("onUpdate", cursor.getString(5))
                            put("onDelete", cursor.getString(6))
                            put("match", cursor.getString(7))
                        }
                    )
                }
            }
            table.put("foreignKeys", foreignKeys)
            tables.put(table)
        }
        root.put("tables", tables)

        val indexes = JSONArray()
        db.rawQuery(
            "SELECT name, tbl_name, COALESCE(sql, '') FROM sqlite_master " +
                "WHERE type = 'index' AND sql IS NOT NULL ORDER BY name",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                indexes.put(
                    JSONObject().apply {
                        put("name", cursor.getString(0))
                        put("table", cursor.getString(1))
                        put("sql", cursor.getString(2))
                    }
                )
            }
        }
        root.put("indexes", indexes)

        return root.toString(2)
    }

    private fun quoteIdentifier(identifier: String): String {
        require(identifier.matches(IDENTIFIER_PATTERN)) {
            "非法 SQL 标识符：$identifier"
        }
        return "\"" + identifier + "\""
    }

    companion object {
        const val DATABASE_NAME = "aster_files.db"
        const val DATABASE_VERSION = 1

        const val TABLE_META = "meta"
        const val TABLE_TRASH_ENTRIES = "trash_entries"
        const val TABLE_INTEGRITY_TASKS = "integrity_tasks"
        const val TABLE_INTEGRITY_RESULTS = "integrity_results"
        const val TABLE_INTEGRITY_ACTION_JOBS = "integrity_action_jobs"
        const val TABLE_AUDIT_LOG = "audit_log"
        const val TABLE_COMPLIANCE_META = "compliance_meta"

        private val IDENTIFIER_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** 7 张表的建表语句，顺序保证外键引用目标先于引用方创建。 */
        val CREATE_TABLE_STATEMENTS: List<String> = listOf(
            """
            CREATE TABLE meta (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE trash_entries (
              trash_id TEXT PRIMARY KEY,
              original_path TEXT NOT NULL,
              trash_path TEXT NOT NULL UNIQUE,
              name TEXT NOT NULL,
              entry_type TEXT NOT NULL,
              size_bytes INTEGER NOT NULL DEFAULT 0,
              deleted_at TEXT NOT NULL,
              restored_at TEXT,
              status TEXT NOT NULL DEFAULT 'ACTIVE'
            )
            """.trimIndent(),
            """
            CREATE TABLE integrity_tasks (
              task_id TEXT PRIMARY KEY,
              scope_json TEXT NOT NULL,
              trigger_type TEXT NOT NULL CHECK (trigger_type = 'MANUAL'),
              status TEXT NOT NULL,
              created_at TEXT NOT NULL,
              started_at TEXT,
              finished_at TEXT,
              total_count INTEGER NOT NULL DEFAULT 0,
              processed_count INTEGER NOT NULL DEFAULT 0,
              current_file TEXT,
              cancel_requested INTEGER NOT NULL DEFAULT 0,
              error TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE integrity_results (
              result_id TEXT PRIMARY KEY,
              task_id TEXT NOT NULL,
              file_id TEXT NOT NULL,
              file_path TEXT NOT NULL,
              file_name TEXT NOT NULL,
              media_type TEXT NOT NULL,
              status TEXT NOT NULL,
              reasons_json TEXT NOT NULL,
              checks_performed_json TEXT NOT NULL,
              detected_at TEXT NOT NULL,
              size_bytes INTEGER NOT NULL,
              hash_before TEXT,
              hash_after TEXT,
              read_only_verified INTEGER NOT NULL,
              duration_ms INTEGER NOT NULL,
              UNIQUE(task_id, file_id),
              FOREIGN KEY(task_id) REFERENCES integrity_tasks(task_id) ON DELETE CASCADE
            )
            """.trimIndent(),
            """
            CREATE TABLE integrity_action_jobs (
              action_job_id TEXT PRIMARY KEY,
              task_id TEXT NOT NULL,
              operation TEXT NOT NULL,
              status TEXT NOT NULL,
              total_count INTEGER NOT NULL,
              processed_count INTEGER NOT NULL,
              current_file TEXT,
              target_directory TEXT,
              cancel_requested INTEGER NOT NULL DEFAULT 0,
              started_at TEXT NOT NULL,
              finished_at TEXT,
              error TEXT,
              items_json TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent(),
            """
            CREATE TABLE audit_log (
              audit_id TEXT PRIMARY KEY,
              timestamp TEXT NOT NULL,
              category TEXT NOT NULL,
              operation TEXT NOT NULL,
              actor TEXT NOT NULL DEFAULT 'local-user',
              task_id TEXT,
              file_id TEXT,
              source_path TEXT,
              target_path TEXT,
              reason TEXT,
              status TEXT NOT NULL,
              detail_json TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE compliance_meta (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """.trimIndent()
        )

        /**
         * 查询索引：不新增表、不新增字段，只优化任务详情、审计列表与回收站查询。
         * 索引也通过 exportSchemaJson 导出，保持文档与实现一致。
         */
        val CREATE_INDEX_STATEMENTS: List<String> = listOf(
            "CREATE INDEX IF NOT EXISTS idx_trash_entries_status " +
                "ON trash_entries(status)",
            "CREATE INDEX IF NOT EXISTS idx_integrity_results_task " +
                "ON integrity_results(task_id)",
            "CREATE INDEX IF NOT EXISTS idx_integrity_results_task_status " +
                "ON integrity_results(task_id, status)",
            "CREATE INDEX IF NOT EXISTS idx_integrity_action_jobs_task " +
                "ON integrity_action_jobs(task_id)",
            "CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp " +
                "ON audit_log(timestamp)"
        )
    }
}
