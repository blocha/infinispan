package org.infinispan.persistence.jdbc.stringbased;

import static org.infinispan.persistence.PersistenceUtil.getExpiryTime;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;

import org.infinispan.commons.configuration.ConfiguredBy;
import org.infinispan.commons.io.ByteBuffer;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.filter.KeyFilter;
import org.infinispan.marshall.core.MarshalledEntry;
import org.infinispan.persistence.TaskContextImpl;
import org.infinispan.persistence.jdbc.JdbcUtil;
import org.infinispan.persistence.jdbc.configuration.JdbcStringBasedStoreConfiguration;
import org.infinispan.persistence.jdbc.logging.Log;
import org.infinispan.persistence.jdbc.common.AbstractJdbcStore;
import org.infinispan.persistence.jdbc.table.management.TableManager;
import org.infinispan.persistence.jdbc.table.management.TableManagerFactory;
import org.infinispan.persistence.keymappers.Key2StringMapper;
import org.infinispan.persistence.keymappers.TwoWayKey2StringMapper;
import org.infinispan.persistence.keymappers.UnsupportedKeyTypeException;
import org.infinispan.persistence.spi.InitializationContext;
import org.infinispan.persistence.spi.PersistenceException;
import org.infinispan.persistence.support.BatchModification;
import org.infinispan.util.KeyValuePair;
import org.infinispan.util.logging.LogFactory;

import javax.transaction.Transaction;

/**
 * {@link org.infinispan.persistence.spi.AdvancedCacheLoader} implementation that stores the entries in a database. In contrast to the
 * {@link org.infinispan.persistence.jdbc.binary.JdbcBinaryStore}, this cache store will store each entry within a row
 * in the table (rather than grouping multiple entries into an row). This assures a finer grained granularity for all
 * operation, and better performance. In order to be able to store non-string keys, it relies on an {@link
 * org.infinispan.persistence.keymappers.Key2StringMapper}.
 * <p/>
 * Note that only the keys are stored as strings, the values are still saved as binary data. Using a character
 * data type for the value column will result in unmarshalling errors.
 * <p/>
 * The actual storage table is defined through configuration {@link org.infinispan.persistence.jdbc.configuration.JdbcStringBasedStoreConfiguration}. The table can
 * be
 * created/dropped on-the-fly, at deployment time. For more details consult javadoc for {@link
 * org.infinispan.persistence.jdbc.configuration.JdbcStringBasedStoreConfiguration}.
 * <p/>
 * It is recommended to use {@link JdbcStringBasedStore}} over
 * {@link org.infinispan.persistence.jdbc.binary.JdbcBinaryStore}} whenever it is possible, as is has a better performance.
 * One scenario in which this is not possible to use it though, is when you can't write an {@link org.infinispan.persistence.keymappers.Key2StringMapper}} to map the
 * keys to to string objects (e.g. when you don't have control over the types of the keys, for whatever reason).
 * <p/>
 * <b>Preload</b>.In order to support preload functionality the store needs to read the string keys from the database and transform them
 * into the corresponding key objects. {@link org.infinispan.persistence.keymappers.Key2StringMapper} only supports
 * key to string transformation(one way); in order to be able to use preload one needs to specify an
 * {@link org.infinispan.persistence.keymappers.TwoWayKey2StringMapper}, which extends {@link org.infinispan.persistence.keymappers.Key2StringMapper} and
 * allows bidirectional transformation.
 * <p/>
 * <b>Rehashing</b>. When a node leaves/joins, Infinispan moves around persistent state as part of rehashing process.
 * For this it needs access to the underlaying key objects, so if distribution is used, the mapper needs to be an
 * {@link org.infinispan.persistence.keymappers.TwoWayKey2StringMapper} otherwise the cache won't start (same constraint as with preloading).
 *
 * @author Mircea.Markus@jboss.com
 * @see org.infinispan.persistence.keymappers.Key2StringMapper
 * @see org.infinispan.persistence.keymappers.DefaultTwoWayKey2StringMapper
 */
@ConfiguredBy(JdbcStringBasedStoreConfiguration.class)
public class JdbcStringBasedStore<K,V> extends AbstractJdbcStore<K,V> {

   private static final Log log = LogFactory.getLog(JdbcStringBasedStore.class, Log.class);
   private static final boolean trace = log.isTraceEnabled();

   private JdbcStringBasedStoreConfiguration configuration;
   private Key2StringMapper key2StringMapper;
   private GlobalConfiguration globalConfiguration;

   public JdbcStringBasedStore() {
      super(log);
   }

   @Override
   public void init(InitializationContext ctx) {
      super.init(ctx);
      configuration = ctx.getConfiguration();
      globalConfiguration = ctx.getCache().getCacheManager().getCacheManagerConfiguration();
   }

   @Override
   public void start() {
      super.start();
      try {
         Object mapper = Util.loadClassStrict(configuration.key2StringMapper(),
                                              globalConfiguration.classLoader()).newInstance();
         if (mapper instanceof Key2StringMapper) key2StringMapper = (Key2StringMapper) mapper;
      } catch (Exception e) {
         log.errorf("Trying to instantiate %s, however it failed due to %s", configuration.key2StringMapper(),
                    e.getClass().getName());
         throw new IllegalStateException("This should not happen.", e);
      }
      if (trace) {
         log.tracef("Using key2StringMapper: %s", key2StringMapper.getClass().getName());
      }
      if (configuration.preload()) {
         enforceTwoWayMapper("preload");
      }
      if (isDistributed()) {
         enforceTwoWayMapper("distribution/rehashing");
      }
   }

   @Override
   public void write(MarshalledEntry entry) {
      Connection connection = null;
      String keyStr = key2Str(entry.getKey());
      try {
         connection = connectionFactory.getConnection();
         write(entry, connection, keyStr);
      } catch (SQLException ex) {
         log.sqlFailureStoringKey(keyStr, ex);
         throw new PersistenceException(String.format("Error while storing string key to database; key: '%s'", keyStr), ex);
      } catch (InterruptedException e) {
         if (trace) {
            log.trace("Interrupted while marshalling to store");
         }
         Thread.currentThread().interrupt();
      } finally {
         connectionFactory.releaseConnection(connection);
      }
   }

   private void write(MarshalledEntry entry, Connection connection) throws SQLException, InterruptedException {
      write(entry, connection, key2Str(entry.getKey()));
   }

   private void write(MarshalledEntry entry, Connection connection, String keyStr) throws SQLException, InterruptedException {
      if (tableManager.isUpsertSupported()) {
         executeUpsert(connection, entry, keyStr);
      } else {
         executeLegacyUpdate(connection, entry, keyStr);
      }
   }

   private void executeUpsert(Connection connection, MarshalledEntry entry, String keyStr)
         throws InterruptedException, SQLException {
      PreparedStatement ps = null;
      String sql = tableManager.getUpsertRowSql();
      if (trace) {
         log.tracef("Running sql '%s'. Key string is '%s'", sql, keyStr);
      } try {
         ps = connection.prepareStatement(sql);
         prepareUpdateStatement(entry, keyStr, ps);
         ps.executeUpdate();
      } finally {
         JdbcUtil.safeClose(ps);
      }
   }

   private void executeLegacyUpdate(Connection connection, MarshalledEntry entry, String keyStr)
         throws InterruptedException, SQLException {
      String sql = tableManager.getSelectIdRowSql();
      if (trace) {
         log.tracef("Running sql '%s'. Key string is '%s'", sql, keyStr);
      }
      PreparedStatement ps = null;
      try {
         ps = connection.prepareStatement(sql);
         ps.setString(1, keyStr);
         ResultSet rs = ps.executeQuery();
         if (rs.next()) {
            sql = tableManager.getUpdateRowSql();
         } else {
            sql = tableManager.getInsertRowSql();
         }
         JdbcUtil.safeClose(rs);
         JdbcUtil.safeClose(ps);
         if (trace) {
            log.tracef("Running sql '%s'. Key string is '%s'", sql, keyStr);
         }
         ps = connection.prepareStatement(sql);
         prepareUpdateStatement(entry, keyStr, ps);
         ps.executeUpdate();
      } finally {
         JdbcUtil.safeClose(ps);
      }
   }

   @Override
   public MarshalledEntry load(Object key) {
      String lockingKey = key2Str(key);
      Connection conn = null;
      PreparedStatement ps = null;
      ResultSet rs = null;
      MarshalledEntry storedValue = null;
      try {
         String sql = tableManager.getSelectRowSql();
         conn = connectionFactory.getConnection();
         ps = conn.prepareStatement(sql);
         ps.setString(1, lockingKey);
         rs = ps.executeQuery();
         if (rs.next()) {
            InputStream inputStream = rs.getBinaryStream(2);
            KeyValuePair<ByteBuffer, ByteBuffer> icv = unmarshall(inputStream);
            storedValue = ctx.getMarshalledEntryFactory().newMarshalledEntry(key, icv.getKey(), icv.getValue());
         }
      } catch (SQLException e) {
         log.sqlFailureReadingKey(key, lockingKey, e);
         throw new PersistenceException(String.format(
               "SQL error while fetching stored entry with key: %s, lockingKey: %s",
               key, lockingKey), e);
      } finally {
         JdbcUtil.safeClose(rs);
         JdbcUtil.safeClose(ps);
         connectionFactory.releaseConnection(conn);
      }
      if (storedValue != null && storedValue.getMetadata() != null &&
            storedValue.getMetadata().isExpired(ctx.getTimeService().wallClockTime())) {
         return null;
      }
      return storedValue;
   }

   @Override
   public boolean delete(Object key) {
      Connection connection = null;
      PreparedStatement ps = null;
      String keyStr = key2Str(key);
      try {
         String sql = tableManager.getDeleteRowSql();
         if (trace) {
            log.tracef("Running sql '%s' on %s", sql, keyStr);
         }
         connection = connectionFactory.getConnection();
         ps = connection.prepareStatement(sql);
         ps.setString(1, keyStr);
         return ps.executeUpdate() == 1;
      } catch (SQLException ex) {
         log.sqlFailureRemovingKeys(ex);
         throw new PersistenceException("Error while removing string keys from database", ex);
      } finally {
         JdbcUtil.safeClose(ps);
         connectionFactory.releaseConnection(connection);
      }
   }

   @Override
   public void purge(Executor executor, PurgeListener task) {
      //todo we should make the notification to the purge listener here
      ExecutorCompletionService<Void> ecs = new ExecutorCompletionService<Void>(executor);
      Future<Void> future = ecs.submit(() -> {
         Connection conn = null;
         PreparedStatement ps = null;
         try {
            String sql = tableManager.getDeleteExpiredRowsSql();
            conn = connectionFactory.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setLong(1, ctx.getTimeService().wallClockTime());
            int result = ps.executeUpdate();
            if (trace) {
               log.tracef("Successfully purged %d rows.", result);
            }
         } catch (SQLException ex) {
            log.failedClearingJdbcCacheStore(ex);
            throw new PersistenceException("Failed clearing string based JDBC store", ex);
         } finally {
            JdbcUtil.safeClose(ps);
            connectionFactory.releaseConnection(conn);
         }
         return null;
      });
      waitForFutureToComplete(future);
   }

   @Override
   public boolean contains(Object key) {
      //we can do better if needed...
      return load(key) != null;
   }

   @Override
   public void process(final KeyFilter filter, final CacheLoaderTask task, Executor executor, final boolean fetchValue, final boolean fetchMetadata) {

      ExecutorCompletionService<Void> ecs = new ExecutorCompletionService<>(executor);
      Future<Void> future = ecs.submit(() -> {
         Connection conn = null;
         PreparedStatement ps = null;
         ResultSet rs = null;
         try {
            String sql = tableManager.getLoadNonExpiredAllRowsSql();
            if (trace) {
               log.tracef("Running sql %s", sql);
            }
            conn = connectionFactory.getConnection();
            ps = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ps.setLong(1, ctx.getTimeService().wallClockTime());
            ps.setFetchSize(tableManager.getFetchSize());
            rs = ps.executeQuery();

            TaskContext taskContext = new TaskContextImpl();
            while (rs.next()) {
               String keyStr = rs.getString(2);
               Object key = ((TwoWayKey2StringMapper) key2StringMapper).getKeyMapping(keyStr);
               if (taskContext.isStopped()) break;
               if (filter != null && !filter.accept(key))
                  continue;
               InputStream inputStream = rs.getBinaryStream(1);
               MarshalledEntry entry;
               if (fetchValue || fetchMetadata) {
                  KeyValuePair<ByteBuffer, ByteBuffer> kvp = unmarshall(inputStream);
                  entry = ctx.getMarshalledEntryFactory().newMarshalledEntry(
                        key, fetchValue ? kvp.getKey() : null, fetchMetadata ? kvp.getValue() : null);
               } else {
                  entry = ctx.getMarshalledEntryFactory().newMarshalledEntry(key, (Object)null, null);
               }
               task.processEntry(entry, taskContext);
            }
            return null;
         } catch (SQLException e) {
            log.sqlFailureFetchingAllStoredEntries(e);
            throw new PersistenceException("SQL error while fetching all StoredEntries", e);
         } finally {
            JdbcUtil.safeClose(rs);
            JdbcUtil.safeClose(ps);
            connectionFactory.releaseConnection(conn);
         }
      });
      waitForFutureToComplete(future);
   }

   @Override
   public void prepareWithModifications(Transaction transaction, BatchModification batchModification) throws PersistenceException {
      try {
         Connection connection = getTxConnection(transaction);
         connection.setAutoCommit(false);

         boolean upsertSupported = tableManager.isUpsertSupported();
         try (PreparedStatement upsertBatch = upsertSupported ? connection.prepareStatement(tableManager.getUpsertRowSql()) : null;
              PreparedStatement deleteBatch = connection.prepareStatement(tableManager.getDeleteRowSql())) {

            for (MarshalledEntry entry : batchModification.getMarshalledEntries()) {
               if (upsertSupported) {
                  String keyStr = key2Str(entry.getKey());
                  prepareUpdateStatement(entry, keyStr, upsertBatch);
                  upsertBatch.addBatch();
               } else {
                  write(entry, connection);
               }
            }

            for (Object key : batchModification.getKeysToRemove()) {
               String keyStr = key2Str(key);
               deleteBatch.setString(1, keyStr);
               deleteBatch.addBatch();
            }

            if (upsertSupported && !batchModification.getMarshalledEntries().isEmpty())
               upsertBatch.executeBatch();

            if (!batchModification.getKeysToRemove().isEmpty())
               deleteBatch.executeUpdate();
         }
         // We do not call connection.close() in the event of an exception, as close() on active Tx behaviour is implementation
         // dependent. See https://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html#close--
      } catch (SQLException | InterruptedException e) {
         throw log.prepareTxFailure(e);
      }
   }

   @Override
   public int size() {
      Connection conn = null;
      PreparedStatement ps = null;
      ResultSet rs = null;
      try {
         conn = connectionFactory.getConnection();
         String sql = tableManager.getCountRowsSql();
         ps = conn.prepareStatement(sql);
         rs = ps.executeQuery();
         rs.next();
         return rs.getInt(1);
      } catch (SQLException e) {
         log.sqlFailureIntegratingState(e);
         throw new PersistenceException("SQL failure while integrating state into store", e);
      } finally {
         JdbcUtil.safeClose(rs);
         JdbcUtil.safeClose(ps);
         connectionFactory.releaseConnection(conn);
      }
   }

   private void prepareUpdateStatement(MarshalledEntry entry, String key, PreparedStatement ps) throws InterruptedException, SQLException {
      ByteBuffer byteBuffer = marshall(new KeyValuePair(entry.getValueBytes(), entry.getMetadataBytes()));
      ps.setBinaryStream(1, new ByteArrayInputStream(byteBuffer.getBuf(), byteBuffer.getOffset(), byteBuffer.getLength()), byteBuffer.getLength());
      ps.setLong(2, getExpiryTime(entry.getMetadata()));
      ps.setString(3, key);
   }

   private String key2Str(Object key) throws PersistenceException {
      if (!key2StringMapper.isSupportedType(key.getClass())) {
         throw new UnsupportedKeyTypeException(key);
      }
      return key2StringMapper.getStringMapping(key);
   }

   public boolean supportsKey(Class<?> keyType) {
      return key2StringMapper.isSupportedType(keyType);
   }

   public TableManager getTableManager() {
      if (tableManager == null)
         tableManager = TableManagerFactory.getManager(connectionFactory, configuration);
      return tableManager;
   }

   private void enforceTwoWayMapper(String where) throws PersistenceException {
      if (!(key2StringMapper instanceof TwoWayKey2StringMapper)) {
         log.invalidKey2StringMapper(where, key2StringMapper.getClass().getName());
         throw new PersistenceException(String.format("Invalid key to string mapper : %s", key2StringMapper.getClass().getName()));
      }
   }

   public boolean isDistributed() {
      return ctx.getCache().getCacheConfiguration() != null && ctx.getCache().getCacheConfiguration().clustering().cacheMode().isDistributed();
   }
}