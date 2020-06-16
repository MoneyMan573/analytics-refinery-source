package org.wikimedia.analytics.refinery.job.refine

/**
  * This file contains objects with apply methods suitable for passing
  * to Refine to do WMF specific transformations on a DataFrame before
  * inserting into a Hive table.
  *
  * After https://gerrit.wikimedia.org/r/#/c/analytics/refinery/source/+/521563/
  * we are merging JSONSchema with Hive schema before we get to these transforms.
  * This means that if there are additional columns on Hive that are not on
  * the JSON Schema they will already be part of the DataFrame (with null values)
  * when we get to these transform functions.
  *
  * Then, if a transform method is the one that determines the value
  * of this Hive-only column, the transform code needs to drop the column
  * (it holds a null value as it has not been populated with schema values)
  * and re-insert it with the calculated value. See geocode_ip function
  * for an example.
  *
  * See the Refine --transform-functions CLI option documentation.
  */

import scala.util.matching.Regex
import org.apache.spark.sql.functions.{expr, udf}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.wikimedia.analytics.refinery.core.{LogHelper, Webrequest}
import org.wikimedia.analytics.refinery.spark.connectors.DataFrameToHive.TransformFunction
import org.wikimedia.analytics.refinery.spark.sql.PartitionedDataFrame
import org.wikimedia.analytics.refinery.spark.sql.HiveExtensions._

import scala.collection.immutable.ListMap
// These aren't directly used, but are referenced in SQL UDFs.
// Import them to get compile time errors if they aren't available.
import org.wikimedia.analytics.refinery.hive.{GetUAPropertiesUDF, IsSpiderUDF, GetGeoDataUDF}


/**
  * Helper wrapper to apply a series of transform functions
  * to PartitionedDataFrames containing 'event' data.
  * This just exists to reduce the number of functions
  * we need to provide to Refine's --transform-functions CLI options.
  */
object event_transforms {
    /**
      * Seq of TransformFunctions that will be applied
      * in order to a PartitionedDataFrame.
      */
    val eventTransformFunctions = Seq[TransformFunction](
        deduplicate.apply,
        filter_allowed_domains.apply,
        geocode_ip.apply,
        parse_user_agent.apply
    )

    def apply(partDf: PartitionedDataFrame): PartitionedDataFrame = {
        eventTransformFunctions.foldLeft(partDf)((currPartDf, fn) => fn(currPartDf))
    }
}


/**
  * Drop duplicate data based on meta.id and/or uuid.
  * - meta.id: Newer (Modern Event Platform) id field.
  * - uuid: Legacy EventLogging Capsule id field.
  *
  * The combination of these columns will be the unique id used for dropping duplicates.
  * If none of these columns exist, this is a no-op.
  */
object deduplicate extends LogHelper {
    val possibleSourceColumnNames = Seq("meta.id", "uuid")

    def apply(partDf: PartitionedDataFrame): PartitionedDataFrame = {
        val sourceColumnNames = partDf.df.findColumnNames(possibleSourceColumnNames)

        // No-op
        if (sourceColumnNames.isEmpty) {
            log.debug(
                s"${partDf.partition} does not contain any id columns named " +
                s"${possibleSourceColumnNames.mkString(" or ")}, cannot deduplicate. Skipping."
            )
            partDf
        } else {
            log.info(s"Dropping duplicates based on ${sourceColumnNames.mkString(",")} columns in ${partDf.partition}")

            // Temporarily add top level columns to the DataFrame
            // with the same values as the sourceColumn.  This allows
            // it to be used as with functions that don't work with
            // nested (struct or map) columns (e.g. dropDuplicates).
            // Fold over sourceColumnNames and accumulate a DataFrame with temporary
            // column names and a Seq of the temp column names that were added.
            var colIndex = 0
            val (tempDf: DataFrame, tempColumnNames: Seq[String]) = sourceColumnNames.foldLeft((partDf.df, Seq.empty[String]))(
                { case ((accDf: DataFrame, accTempNames: Seq[String]), sourceColumnName: String) =>
                    // add a temp column for sourceColumnName
                    val flattenedSourceColumnName = sourceColumnName.replaceAll("\\.", "_")
                    val tempName = s"__temp__${flattenedSourceColumnName}__for_deduplicate_${colIndex}"
                    colIndex += 1
                    // Accumulate the df with temp column and a list of temp columns names added
                    (
                        accDf.withColumn(tempName, accDf(sourceColumnName)),
                        accTempNames :+ tempName
                    )
            })

            partDf.copy(df=tempDf
                // Drop duplicate records based on tempColumnNames we added to the df.
                .dropDuplicates(tempColumnNames)
                // And drop the tempColumnNames from the result.
                .drop(tempColumnNames:_*)
            )
        }
    }
}

/**
  * Backwards compatible function name for EventLogging refine jobs.
  * This will be removed once event Refine job configurations are unified.
  */
object deduplicate_eventlogging extends LogHelper {
    def apply(partDf: PartitionedDataFrame): PartitionedDataFrame = {
        deduplicate(partDf)
    }
}

/**
  * Backwards compatible function name for eventbus refine jobs.
  * This will be removed once event Refine job configurations are unified.
  */
object deduplicate_eventbus extends LogHelper {
    def apply(partDf: PartitionedDataFrame): PartitionedDataFrame = {
        deduplicate(partDf)
    }
}

/**
  * Geocodes an ip address column into a new  `geocoded_data` Map[String, String] column
  * using MaxmindDatabaseReaderFactory.
  *
  * The ip address value used will be extracted from one of the following columns:
  * - http.client_ip: Newer Modern Event Platform schemas uses this
  * - ip: legacy eventlogging capsule has this
  * - client_ip: client_ip: used in webrequest table, here just in case something else uses it too.
  *
  * In the order listed above, the first non null column value in the input DataFrame
  * records will be used for geocoding.
  * If none of these fields are present in the DataFrame schema, this is a no-op.
  */
object geocode_ip extends LogHelper {
    val possibleSourceColumnNames = Seq("http.client_ip", "ip", "client_ip")
    val geocodedDataColumnName    = "geocoded_data"

    // eventlogging-processor would stick the client IP into a top level 'ip' column.
    // To be backwards compatible, if this column exists and does not have data, we
    // should fill it with the first non null value of one of the possibleSourceColumnNames.
    val ipLegacyColumnName = "ip"

    def apply(partDf: PartitionedDataFrame): PartitionedDataFrame = {
        val spark = partDf.df.sparkSession
        val sourceColumnNames = partDf.df.findColumnNames(possibleSourceColumnNames)

        // No-op
        if (sourceColumnNames.isEmpty) {
            log.debug(
                s"${partDf.partition} does not contain any ip columns named " +
                s"${possibleSourceColumnNames.mkString(" or ")}, cannot geocode. Skipping."
            )
            partDf
        } else {
            // If there is only one possible source column, just
            // use it when geocoding.  Else, we need to COALESCE and use the
            // first non null value chosen from possible source columns in each record.
            val sourceColumnSql = sourceColumnNames match {
                case Seq(singleColumnName) => singleColumnName
                case _ => s"COALESCE(${sourceColumnNames.mkString(",")})"
            }

            log.info(
                s"Geocoding $sourceColumnSql into `$geocodedDataColumnName` in ${partDf.partition}"
            )
            spark.sql(
                "CREATE OR REPLACE TEMPORARY FUNCTION get_geo_data AS " +
                "'org.wikimedia.analytics.refinery.hive.GetGeoDataUDF'"
            )

            // Don't try to geocode records where sourceColumnSql is NULL.
            val geocodedDataSql = s"(CASE WHEN $sourceColumnSql IS NULL THEN NULL ELSE get_geo_data($sourceColumnSql) END) AS $geocodedDataColumnName"

            // Select all columns (except for any pre-existing geocodedDataColumnName) with
            // the result of as geocodedDataSql as geocodedDataColumnName.
            val workingDf = partDf.df
            val columnExpressions = workingDf.columns.filter(
                _.toLowerCase != geocodedDataColumnName.toLowerCase
            ) :+ geocodedDataSql
            val parsedDf = workingDf.selectExpr(columnExpressions:_*)


            // If the original DataFrame has a legacy ip, copy the source column value to it
            // for backwards compatibility.
            if (parsedDf.hasColumn(ipLegacyColumnName)) {
                add_legacy_eventlogging_ip(partDf.copy(df = parsedDf), sourceColumnSql)
            }
            else {
                partDf.copy(df = parsedDf)
            }
        }
    }

    /**
      * EventLogging legacy data had an 'ip' column that was set by server side
      * eventlogging-processor.  This function sets it from sourceColumnSql, unless
      * the legacy ip column already has a non null value.
      *
      * @param partDf
      * @param sourceColumnSql
      * @return
      */
    private def add_legacy_eventlogging_ip(
        partDf: PartitionedDataFrame,
        sourceColumnSql: String
    ): PartitionedDataFrame = {
        val spark = partDf.df.sparkSession

        // If ipLegacyColumnName exists and is non NULL, keep it, otherwise set it
        // to the value of sourceColumnSql.
        // This handles the case where we have an (externally eventlogging-processor) parsed ip
        // field, so we shouldn't touch it.
        val ipSql =
            s"""
               |COALESCE(
               |    $ipLegacyColumnName,
               |    $sourceColumnSql
               |) AS $ipLegacyColumnName
               |""".stripMargin

        log.info(
            s"Setting `$ipLegacyColumnName` column in ${partDf.partition} " +
                s"using SQL:\n$ipSql"
        )

        val workingDf = partDf.df

        // Select all columns except for any pre-existing userAgentStructLegacyColumnName with
        // the result of as userAgentStructSql as userAgentStructLegacyColumnName.
        val columnExpressions = workingDf.columns.filter(
            _.toLowerCase != ipLegacyColumnName.toLowerCase
        ) :+ ipSql
        partDf.copy(df = workingDf.selectExpr(columnExpressions:_*))
    }
}

/**
  * Parses user agent into the user_agent_map field using
  * org.wikimedia.analytics.refinery.hive.GetUAPropertiesUDF.
  * If the incoming DataFrame schema has a legacy eventlogging `useragent` struct column,
  * it will also be generated using the values in the parsed user_agent_map as well
  * as some extra logic to add the is_mediawiki and is_bot struct fields.
  *
  * The user agent string will be extracted from one of the following columns:
  * - http.request_headers['user-agent']
  *
  * In the order listed above, the first non null column value in the input DataFrame
  * records will be used for user agent parsing.
  * If none of these fields are present in the DataFrame schema, this is a no-op.
  */


object parse_user_agent extends LogHelper {
    // Only one possible source column currently, but we could add more.
    val possibleSourceColumnNames = Seq("http.request_headers.`user-agent`")
    val userAgentMapColumnName    = "user_agent_map"
    // This camelCase userAgent case sensitive name is really a pain.
    // df.hasColumn is case-insenstive, but accessing StructType schema fields
    // by name is not.
    val userAgentStructLegacyColumnName = "useragent"

    def apply(partDf: PartitionedDataFrame): PartitionedDataFrame = {
        val spark = partDf.df.sparkSession
        val sourceColumnNames = partDf.df.findColumnNames(possibleSourceColumnNames)

        // No-op
        if (sourceColumnNames.isEmpty) {
            log.debug(
                s"${partDf.partition} does not contain any columns named " +
                s"${possibleSourceColumnNames.mkString(" or ")}. " +
                 "Cannot parse user agent. Skipping."
            )
            partDf
        } else {
            // If there is only one possible source column, just
            // use it when parsing.  Else, we need to COALESCE and use the
            // first non null value chosen from possible source columns in each record.
            val sourceColumnSql = sourceColumnNames match {
                case Seq(singleColumnName) => singleColumnName
                case _ => s"COALESCE(${sourceColumnNames.mkString(",")})"
            }
            log.info(
                s"Parsing $sourceColumnSql into `$userAgentMapColumnName` in ${partDf.partition}"
            )
            spark.sql(
                "CREATE OR REPLACE TEMPORARY FUNCTION get_ua_properties AS " +
                "'org.wikimedia.analytics.refinery.hive.GetUAPropertiesUDF'"
            )
            val userAgentMapSql = s"(CASE WHEN $sourceColumnSql IS NULL THEN NULL ELSE get_ua_properties($sourceColumnSql) END) AS $userAgentMapColumnName"

            // Select all columns except for any pre-existing userAgentMapColumnName with
            // the result of as userAgentMapSql as userAgentMapColumnName.
            val workingDf = partDf.df
            val columnExpressions = workingDf.columns.filter(
                _.toLowerCase != userAgentMapColumnName.toLowerCase
            ) :+ userAgentMapSql
            val parsedDf = workingDf.selectExpr(columnExpressions:_*)

            // If the original DataFrame has a legacy useragent struct field, copy the map field
            // entries to it for backwards compatibility.
            if (
                parsedDf.hasColumn(userAgentStructLegacyColumnName) &&
                // (We need to find the field in the schema case insensitive)
                parsedDf.schema.find(Seq(userAgentStructLegacyColumnName), true).head.isStructType
            ) {
                add_legacy_eventlogging_struct(partDf.copy(df = parsedDf), sourceColumnSql)
            }
            else {
                partDf.copy(df = parsedDf)
            }
        }
    }


    /**
      * eventlogging-processor previously handled user agent parsing and
      * added the 'userAgent' field as a JSON object, which got inferred as a struct
      * in the event schema by Refine jobs.  There may be existent uses of
      * the useragent struct field in Hive, so we need to keep ensuring
      * that a useragent struct exists.  This function generates it from
      * the user_agent_map entries and also adds is_mediawiki and is_bot fields.
      *
      * If a record already has a non NULL useragent struct, it is left intact,
      * otherwise it will be created from the values of user_agent_map.
      *
      * @param partDf input PartitionedDataFrame
      * @param sourceColumnSql SQL string (or just column name) in partDf.df to get user agent string.
      *                     This is passed to the IsSpiderUDF.
      */
    private def add_legacy_eventlogging_struct(
        partDf: PartitionedDataFrame,
        sourceColumnSql: String
    ): PartitionedDataFrame = {
        val spark = partDf.df.sparkSession

        // IsSpiderUDF is used to calculate is_bot value.
        spark.sql(
            "CREATE OR REPLACE TEMPORARY FUNCTION is_spider AS " +
            "'org.wikimedia.analytics.refinery.hive.IsSpiderUDF'"
        )

        // useragent needs to be a struct with these values from UAParser returned user_agent_map,
        // as well as boolean values for is_mediawiki and is_bot.
        // See legacy eventlogging parser code at
        // https://github.com/wikimedia/eventlogging/blob/master/eventlogging/utils.py#L436-L454
        // This is a map from struct column name to SQL that gets the value for that column.
        val userAgentLegacyNamedStructFieldSql = ListMap(
            "browser_family" -> s"$userAgentMapColumnName.`browser_family`",
            "browser_major" -> s"$userAgentMapColumnName.`browser_major`",
            "browser_minor" -> s"$userAgentMapColumnName.`browser_minor`",
            "device_family" -> s"$userAgentMapColumnName.`device_family`",
            // is_bot is true if device_family is Spider, or if device_family is Other and
            // the user agent string matches the spider regex defined in refinery Webrequest.java.
            "is_bot" -> s"""boolean(
                           |            $userAgentMapColumnName.`device_family` = 'Spider' OR (
                           |                $userAgentMapColumnName.`device_family` = 'Other' AND
                           |                is_spider($sourceColumnSql)
                           |            )
                           |        )""".stripMargin,
            // is_mediawiki is true if the user agent string contains 'mediawiki'
            "is_mediawiki" -> s"boolean(lower($sourceColumnSql) LIKE '%mediawiki%')",
            "os_family" -> s"$userAgentMapColumnName.`os_family`",
            "os_major" -> s"$userAgentMapColumnName.`os_major`",
            "os_minor" -> s"$userAgentMapColumnName.`os_minor`",
            "wmf_app_version" -> s"$userAgentMapColumnName.`wmf_app_version`"
        )
        // Convert userAgentLegacyNamedStructFieldSql to a named_struct SQL string.
        val namedStructSql = "named_struct(\n        " + userAgentLegacyNamedStructFieldSql.map({
            case (fieldName, sql) =>
                s"'$fieldName', $sql"
            }).mkString(",\n        ") + "\n    )"

        // Build a SQL statement using named_struct that will generate the
        // userAgentStructLegacyColumnName struct.
        // If userAgentStructLegacyColumnName exists and is non NULL, keep it.
        // This handles the case where the value of sourceColumnSql might be null, but
        // we have an (externally eventlogging-processor) parsed userAgent EventLogging field,
        // so we don't need to and can't reparse it, since we don't have the original user agent.

        // If useragent struct, keep it,
        // Else if user_agent_map is NULL, then set useragent to NULL too.
        // Else create useragent struct from user_agent_map
        val userAgentStructSql =
            s"""
               |COALESCE(
               |    $userAgentStructLegacyColumnName,
               |    CASE WHEN $userAgentMapColumnName IS NULL THEN NULL ELSE $namedStructSql END
               |) AS $userAgentStructLegacyColumnName
               |""".stripMargin

        log.info(
            s"Adding legacy `$userAgentStructLegacyColumnName` struct column in ${partDf.partition} " +
            s"using SQL:\n$userAgentStructSql"
        )

        val workingDf = partDf.df
        // Select all columns except for any pre-existing userAgentStructLegacyColumnName with
        // the result of as userAgentStructSql as userAgentStructLegacyColumnName.
        val columnExpressions = workingDf.columns.filter(
            _.toLowerCase != userAgentStructLegacyColumnName.toLowerCase
        ) :+ userAgentStructSql
        partDf.copy(df = workingDf.selectExpr(columnExpressions:_*))
    }
}


/**
  * Filters out records from a domain that is not a wiki
  * except if those records match domains on a whitelist.
  *
  * Accepted values include:
  *   wikipedia.org, en.wiktionary.org, ro.m.wikibooks,
  *   zh-an.wikivoyage.org, mediawiki.org, www.wikidata.org,
  *   translate.google, etc.
  *
  * Filtered out values include:
  *   en-wiki.org, en.wikipedia.nom.it, en.wikipedi0.org,
  *   www.translatoruser-int.com, etc.
  *
  * Given that domain columns are optional fields we need to accept
  * as valid records for which domain is null.
  *
  * Possible domain columns:
  * - meta.domain: newer (Modern Event Platform) events use this
  * - webHost: legacy EventLogging Capsule data.
  *
  * In the order listed above, the first non null column value in the input DataFrame
  * records will be used for filtering.
  * If none of these fields exist in the input DataFrame schema, this is a no-op.
  */
object filter_allowed_domains extends LogHelper {
    val possibleSourceColumnNames = Seq("meta.domain", "webHost")

    // TODO: If this changes frequently data for whitelist should
    //       probably come from hive.
    //
    // TODO: These match things like 'fake.translate.google.test.com' or
    //       'fake.www.wikipedia.org.test.com'. Do we want to do this?
    //       s"^(${List("translate.google", "www.wikipedia.org").mkString("|")})$$".r ?s
    var whitelist: Regex = List("translate.google", "www.wikipedia.org").mkString("|").r;

    val isAllowedDomain: UserDefinedFunction = udf(
        (domain: String) => {
            if (domain == null || domain.isEmpty) true
            else if (whitelist.findFirstMatchIn(domain.toLowerCase()).isDefined) true
            else if (Webrequest.isWikimediaHost(domain)) true
            else false
        }
    )

    def apply(partDf: PartitionedDataFrame): PartitionedDataFrame = {
        // We don't need to check case insensitively here because
        // column access on a DataFrame is case insensitive already.
        val sourceColumnNames = partDf.df.findColumnNames(possibleSourceColumnNames)

        // No-op
        if (sourceColumnNames.isEmpty) {
            log.debug(
                s"${partDf.partition} does not have any column " +
                s"${possibleSourceColumnNames.mkString(" or ")}, not filtering for allowed domains."
            )
            partDf
        } else {
            // If there is only one possible source column, just
            // use it when filtering.  Else, we need to COALESCE and use the
            // first non null value chosen from possible source columns in each record.
            val sourceColumnSql = sourceColumnNames match {
                case Seq(singleColumnName) => singleColumnName
                case _ => s"COALESCE(${sourceColumnNames.mkString(",")})"
            }
            log.info(
                s"Filtering for allowed domains in $sourceColumnSql in ${partDf.partition}."
            )
            partDf.copy(df = partDf.df.filter(isAllowedDomain(expr(sourceColumnSql))))
        }
    }
}

/**
  * Backwards compatible name of this function for legacy EventLogging jobs.
  * This will be removed once event Refine job configurations are unified.
  */
object eventlogging_filter_is_allowed_hostname extends LogHelper {
    def apply(partDf: PartitionedDataFrame): PartitionedDataFrame = {
        filter_allowed_domains.apply(partDf)
    }
}
