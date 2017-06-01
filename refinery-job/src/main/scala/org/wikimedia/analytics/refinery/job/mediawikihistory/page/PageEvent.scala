package org.wikimedia.analytics.refinery.job.mediawikihistory.page

import org.wikimedia.analytics.refinery.job.mediawikihistory.utils.Edge

/**
  * This case class represents a page event object, by opposition
  * to a page state object. It extends [[Edge]] (for graph partitioning)
  * with [[fromKey]] defined as (WikiDb, oldTitle, oldNamespace) and [[toKey]]
  * defined as (WikiDb, newTitle, newNamespace).
  */
case class PageEvent(
    // Generic Fields
    wikiDb: String,
    timestamp: String,
    eventType: String,
    causedByUserId: Option[Long] = None,
    parsingErrors: Seq[String] = Seq.empty[String],
    // Specific fields
    pageId: Option[Long] = None,
    oldTitle: String,
    newTitle: String,
    newTitlePrefix: String,
    newTitleWithoutPrefix: String,
    oldNamespace: Int,
    oldNamespaceIsContent: Boolean,
    newNamespace: Int,
    newNamespaceIsContent: Boolean
) extends Edge[(String, String, Int)] {
  override def fromKey: (String, String, Int) = (wikiDb, oldTitle, oldNamespace)
  override def toKey: (String, String, Int) = (wikiDb, newTitleWithoutPrefix, newNamespace)
}