package com.tinkercobble.ham.data

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** A reviewed, single-occurrence copy with no source provider identity or attachment reference. */
object EventCopyDraft {
    fun from(event: AgendaEvent, zone: ZoneId = ZoneId.systemDefault()): IcsImportDraft =
        IcsSingleEventImport.preview(IcsSingleEventExport.render(event, UUID.randomUUID(), Instant.now()), zone)
}
