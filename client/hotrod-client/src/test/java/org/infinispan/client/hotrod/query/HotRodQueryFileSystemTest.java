package org.infinispan.client.hotrod.query;

import org.infinispan.commons.util.Util;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.test.TestingUtil;
import org.testng.annotations.Test;

import java.io.File;

import static org.testng.AssertJUnit.assertTrue;

/**
 * Tests verifying the functionality of Remote queries for HotRod using FileSystem as a directory provider.
 *
 * @author Anna Manukyan
 * @author anistor@redhat.com
 */
@Test(testName = "client.hotrod.query.HotRodQueryFileSystemTest", groups = "functional")
public class HotRodQueryFileSystemTest extends HotRodQueryTest {

   private final String indexDirectory = TestingUtil.tmpDirectory(getClass());

   @Override
   protected ConfigurationBuilder getConfigurationBuilder() {
      ConfigurationBuilder builder = super.getConfigurationBuilder();
      builder.indexing()
            .addProperty("default.directory_provider", "filesystem")
            .addProperty("default.indexBase", indexDirectory);
      return builder;
   }

   @Override
   protected void setup() throws Exception {
      Util.recursiveFileRemove(indexDirectory);
      boolean created = new File(indexDirectory).mkdirs();
      assertTrue(created);
      super.setup();
   }

   @Override
   protected void teardown() {
      try {
         //first stop cache managers, then clear the index
         super.teardown();
      } finally {
         //delete the index otherwise it will mess up the index for next tests
         Util.recursiveFileRemove(indexDirectory);
      }
   }
}
