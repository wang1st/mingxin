package com.mingxin.feynman.data

/** 按经 → 专题分组 */
data class TopicGroup(val sutra: String, val topic: String, val questions: List<QuestionEntity>)

fun List<QuestionEntity>.sutrasOf(): List<String> =
    map { it.sutra }.distinct()

fun List<QuestionEntity>.topicsOf(sutra: String): List<TopicGroup> =
    filter { it.sutra == sutra }
        .groupBy { it.topic }
        .map { (t, qs) -> TopicGroup(sutra, t, qs.sortedBy { it.id }) }

fun List<QuestionEntity>.byId(id: String): QuestionEntity? =
    if (id.isBlank()) null else firstOrNull { it.id == id }

fun QuestionEntity.followupList(): List<String> =
    followups.split("\n").filter { it.isNotBlank() }
