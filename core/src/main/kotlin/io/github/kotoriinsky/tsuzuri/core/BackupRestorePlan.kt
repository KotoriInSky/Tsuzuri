package io.github.kotoriinsky.tsuzuri.core

/** 恢复先全量校验，再把完整计划交给存储层一次写入，坏尾项不能留下半份配置。 */
object BackupRestorePlan {
    data class Item(val key: String, val type: String, val value: Any?)

    fun validate(version: Int, items: List<Item>): Map<String, Any> {
        require(version == 1) { "不认识的备份版本：$version" }
        require(items.size <= 4096) { "备份项目过多" }
        val values = linkedMapOf<String, Any>()
        for ((index, item) in items.withIndex()) {
            require(item.key.isNotBlank() && item.key.length <= 256 && item.key.none { it.isISOControl() }) { "第 ${index + 1} 项键名无效" }
            require(item.key !in values) { "备份包含重复键：${item.key}" }
            val value = item.value
            val normalized: Any = when (item.type) {
                "Boolean" -> { require(value is Boolean); value }
                "String" -> { require(value is String); value }
                "Integer" -> {
                    require(value is Number)
                    val number = value.toDouble()
                    require(number.isFinite() && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE && number == number.toInt().toDouble())
                    number.toInt()
                }
                "Long" -> {
                    require(value is Number)
                    when (value) {
                        is Long -> value
                        is Int -> value.toLong()
                        else -> {
                            val number = value.toDouble()
                            require(number.isFinite() && number >= -9223372036854775808.0 && number < 9223372036854775808.0 && number == number.toLong().toDouble())
                            number.toLong()
                        }
                    }
                }
                "Float" -> {
                    require(value is Number && value.toDouble().isFinite() && value.toFloat().isFinite())
                    value.toFloat()
                }
                "Set" -> {
                    require(value is List<*> && value.all { it is String })
                    value.filterIsInstance<String>().toSet()
                }
                else -> throw IllegalArgumentException("第 ${index + 1} 项类型不认识：${item.type}")
            }
            values[item.key] = normalized
        }
        return values
    }

    fun restore(version: Int, items: List<Item>, write: (Map<String, Any>) -> Unit): Int {
        val values = validate(version, items)
        if (values.isNotEmpty()) write(values)
        return values.size
    }
}
