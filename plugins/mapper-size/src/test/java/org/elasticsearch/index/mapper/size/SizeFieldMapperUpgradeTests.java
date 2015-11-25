/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper.size;

import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.TestUtil;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.plugin.mapper.MapperSizePlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.hamcrest.ElasticsearchAssertions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0)
@LuceneTestCase.SuppressFileSystems("ExtrasFS")
public class SizeFieldMapperUpgradeTests extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.<Class<? extends Plugin>>singleton(MapperSizePlugin.class);
    }

    public void doTestUpgradeOldMapping(String version) throws IOException {
        final String indexName = "index-mapper-size-" + version;
        Path unzipDir = createTempDir();
        Path unzipDataDir = unzipDir.resolve("data");
        Path backwardsIndex = getBwcIndicesPath().resolve(indexName + ".zip");
        try (InputStream stream = Files.newInputStream(backwardsIndex)) {
            TestUtil.unzip(stream, unzipDir);
        }
        assertTrue(Files.exists(unzipDataDir));

        Path dataPath = createTempDir();
        Settings settings = Settings.builder()
                .put("path.data", dataPath)
                .build();
        final String node = internalCluster().startNode(settings);
        Path[] nodePaths = internalCluster().getInstance(NodeEnvironment.class, node).nodeDataPaths();
        assertEquals(1, nodePaths.length);
        dataPath = nodePaths[0].resolve(NodeEnvironment.INDICES_FOLDER);
        assertFalse(Files.exists(dataPath));
        Path src = unzipDataDir.resolve(indexName + "/nodes/0/indices");
        Files.move(src, dataPath);

        ensureYellow();
        final SearchResponse countResponse = client().prepareSearch(indexName).setSize(0).get();
        ElasticsearchAssertions.assertHitCount(countResponse, 3L);

        final SearchResponse sizeResponse = client().prepareSearch(indexName)
                .addField("_source")
                .addFieldDataField("_size")
                .get();
        ElasticsearchAssertions.assertHitCount(sizeResponse, 3L);
        for (SearchHit hit : sizeResponse.getHits().getHits()) {
            String source = hit.getSourceAsString();
            assertNotNull(source);
            Map<String, SearchHitField> fields = hit.getFields();
            assertTrue(fields.containsKey("_size"));
            Number size = fields.get("_size").getValue();
            assertNotNull(size);
            assertEquals(source.length(), size.longValue());
        }
    }

    public void testUpgradeOldMapping200() throws IOException {
        doTestUpgradeOldMapping("2.0.0");
    }

    public void testUpgradeOldMapping173() throws IOException {
        doTestUpgradeOldMapping("1.7.3");
    }
}