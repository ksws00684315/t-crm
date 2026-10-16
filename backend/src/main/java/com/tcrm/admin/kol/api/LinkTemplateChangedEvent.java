package com.tcrm.admin.kol.api;

/**
 * Published inside the transaction that changes a link template's URL or status (task 11 §4.4). The
 * link index listener registers one outbox row per KOL using the template, before commit, so their
 * link hashes are refreshed afterwards. Name and sort changes do not affect targets and do not
 * publish this event.
 *
 * @param templateId crm_link_template.id
 */
public record LinkTemplateChangedEvent(Long templateId) {}
