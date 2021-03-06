/**
 * Copyright (C) 2014  Wikimedia Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wikimedia.analytics.refinery.hive;

import org.apache.hadoop.hive.ql.exec.UDF;
import org.wikimedia.analytics.refinery.core.SearchRequest;

/**
 * Deprecated - Use GetSearchRequestTypeUDF
 * A hive UDF to identify what type of search API the request uses,
 * e.g. cirrus (aka full-text), prefix, or geo/open/language search.
 */

@Deprecated
public class SearchClassifierUDF extends UDF {
    public String evaluate(
        String uriPath,
        String uriQuery
    ) {
        SearchRequest search_inst = SearchRequest.getInstance();
        return search_inst.classifySearchRequest(
            uriPath,
            uriQuery
        );
    }
}
