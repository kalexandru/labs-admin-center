package com.engagepoint.university.admincentre.dao;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Observable;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.query.Search;
import org.infinispan.query.SearchManager;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.engagepoint.university.admincentre.entity.AbstractEntity;
import com.engagepoint.university.admincentre.entity.Node;
import com.engagepoint.university.admincentre.synchronization.CRUDObserver;
import com.engagepoint.university.admincentre.synchronization.CRUDOperation;
import com.engagepoint.university.admincentre.synchronization.CRUDPayload;
import com.engagepoint.university.admincentre.synchronization.SynchMaster;
import com.engagepoint.university.admincentre.util.ConfLoader;

public abstract class AbstractDAO<T extends AbstractEntity> extends Observable implements
        GenericDAO<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractDAO.class);
    private static final String CACHE_CONFIG = "cache_config.xml";
    private static final String USED_CACHE = "evictionCache";
    private DefaultCacheManager m = null;
    private Cache<String, T> cache = null;
    private Class<T> type;

    public AbstractDAO(Class<T> type) {
        this.type = type;
        addObserver(new CRUDObserver());
    }

    public synchronized void create(T newInstance) throws IOException {
        try {
            getCache(CACHE_CONFIG, USED_CACHE);
            String instanceId = newInstance.getId();
            if (!cache.containsKey(instanceId)) {
                cache.put(instanceId, newInstance);
                tryToSend(CRUDOperation.CREATE, newInstance);
            } else {
                throw new IOException("This entity already exists" + newInstance.getName());
            }

        } finally {

            stopCacheManager();
        }
    }

    @Override
    public synchronized T read(String id) throws IOException {
        try {
            T variable = null;
            getCache(CACHE_CONFIG, USED_CACHE);
            if (cache.containsKey(id)) {
                variable = cache.get(id);
            }
            return variable;
        } finally {
            stopCacheManager();
        }
    }

    @Override
    public synchronized void update(T transientObject) throws IOException {
        try {
            getCache(CACHE_CONFIG, USED_CACHE);
            cache.replace(transientObject.getId(), transientObject);
            tryToSend(CRUDOperation.UPDATE, transientObject);
        } finally {
            stopCacheManager();
        }
    }

    @Override
    public synchronized void delete(String keyId) throws IOException {
        try {
            getCache(CACHE_CONFIG, USED_CACHE);
            T temp = cache.get(keyId);
            if (temp == null) {
                throw new IOException();
            }
            cache.remove(keyId);
            tryToSend(CRUDOperation.DELETE, temp);
        } finally {
            stopCacheManager();
        }
    }

    @Override
    public synchronized List<T> search(String name) throws IOException {
        try {
            getCache(CACHE_CONFIG, USED_CACHE);
            SearchManager searchManager = Search.getSearchManager(cache);
            QueryFactory qf = searchManager.getQueryFactory();
            Query query = qf.from(type).having("name").like("*" + name + "*").toBuilder().build();
            List<T> resultList = query.list();
            if (!resultList.isEmpty()) {
                return resultList;
            }
            return new LinkedList<T>();
        } finally {
            stopCacheManager();
        }
    }

    private synchronized void getCache(String cacheConfigPath, String cacheName) throws IOException {
        if (m == null) {

            m = new DefaultCacheManager(cacheConfigPath);

            Configuration rc = m.getCacheConfiguration(cacheName);
            Configuration config = new ConfigurationBuilder().read(rc).jmxStatistics()
                    .enabled(true).persistence()
                    .addSingleFileStore().fetchPersistentState(true).preload(true)
                    .ignoreModifications(false).purgeOnStartup(false).shared(false)
                    .location(ConfLoader.getInstance().getBasePath())
                    .build();

            m.defineConfiguration(cacheName, config);
            cache = m.getCache(cacheName);
            if (!cache.containsKey("/")) {
                Cache<String, Node> startCache = m.getCache(cacheName);
                Node node = new Node("/", "");
                startCache.put(node.getId(), node);
            }
        } else {
            cache.start();
        }
    }

    private synchronized void stopCacheManager() {
        if (m != null) {
            cache.stop();
        }
    }

    /**
     * Puts new received cache
     *
     * @param cacheData
     * @throws IOException
     * @throws UnsupportedOperationException if could not put all
     */
    public synchronized void putAll(Map<String, T> cacheData) throws IOException {

        try {
            getCache(CACHE_CONFIG, USED_CACHE);
            cache.putAll(cacheData);
        } catch (IOException e) {
            throw new IOException("Could not put all, using cache: " + cache.getName(), e);
        } finally {
            stopCacheManager();
        }
    }

    /**
     * Clears cache
     *
     * @throws IOException
     * @throws UnsupportedOperationException if cache could not be clear
     */
    public synchronized void clear() throws IOException {

        try {
            getCache(CACHE_CONFIG, USED_CACHE);
            cache.clear();
        } catch (IOException e) {
            throw new IOException("Cache could not be cleared", e);
        } finally {
            stopCacheManager();
        }
    }

    public synchronized Map<String, T> obtainCache() throws IOException {
        getCache(CACHE_CONFIG, USED_CACHE);
        return cache;
    }

    private void tryToSend(CRUDOperation crudOperation, T t) {
        try {
            if (SynchMaster.connected()
                    && SynchMaster.getInstance().isConnected()
                    && !SynchMaster.getInstance().isSingle()) {
                setChanged();
                notifyObservers(new CRUDPayload(crudOperation, t));
            }
        } catch (Exception e) {
            LOGGER.error("exception when {} {} was occured, sychroniztion might not work.",
                    crudOperation, t.toString(), e);
        }
    }
}
