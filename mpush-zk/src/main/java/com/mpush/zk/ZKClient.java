/*
 * (C) Copyright 2015-2016 the original author or authors.
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
 *
 * Contributors:
 *   ohun@live.cn (夜色)
 */

package com.mpush.zk;

import com.mpush.api.Constants;
import com.mpush.api.service.BaseService;
import com.mpush.api.service.Listener;
import com.mpush.tools.log.Logs;
import com.mpush.zk.listener.ZKNodeCacheWatcher;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.CuratorFrameworkFactory.Builder;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ZKClient extends BaseService {
    public static final ZKClient I = I();
    private ZKConfig zkConfig;
    private CuratorFramework client;
    private TreeCache cache;

    private synchronized static ZKClient I() {
        return I == null ? new ZKClient() : I;
    }

    private ZKClient() {
    }

    @Override
    protected void doStart(Listener listener) throws Throwable {
        client.start();
        Logs.Console.info("init zk client waiting for connected...");
        if (!client.blockUntilConnected(1, TimeUnit.MINUTES)) {
            throw new ZKException("init zk error, config=" + zkConfig);
        }
        initLocalCache(zkConfig.getLocalCachePath());
        addConnectionStateListener();
        listener.onSuccess(zkConfig.getHosts());
        Logs.ZK.info("zk client start success, server lists is:{}", zkConfig.getHosts());
        Logs.Console.info("init zk client success...");
    }

    @Override
    protected void doStop(Listener listener) throws Throwable {
        if (cache != null) cache.close();
        TimeUnit.MILLISECONDS.sleep(600);
        client.close();
    }

    /**
     * 初始化
     */
    @Override
    public void init() {
        if (zkConfig != null) return;
        zkConfig = ZKConfig.build();
        Builder builder = CuratorFrameworkFactory
                .builder()
                .connectString(zkConfig.getHosts())
                .retryPolicy(new ExponentialBackoffRetry(zkConfig.getBaseSleepTimeMs(), zkConfig.getMaxRetries(), zkConfig.getMaxSleepMs()))
                .namespace(zkConfig.getNamespace());

        if (zkConfig.getConnectionTimeout() > 0) {
            builder.connectionTimeoutMs(zkConfig.getConnectionTimeout());
        }
        if (zkConfig.getSessionTimeout() > 0) {
            builder.sessionTimeoutMs(zkConfig.getSessionTimeout());
        }

        if (zkConfig.getDigest() != null) {
            builder.authorization("digest", zkConfig.getDigest().getBytes(Constants.UTF_8))
                    .aclProvider(new ACLProvider() {

                        @Override
                        public List<ACL> getDefaultAcl() {
                            return ZooDefs.Ids.CREATOR_ALL_ACL;
                        }

                        @Override
                        public List<ACL> getAclForPath(final String path) {
                            return ZooDefs.Ids.CREATOR_ALL_ACL;
                        }
                    });
        }
        client = builder.build();
        Logs.Console.info("init zk client, config={}", zkConfig.toString());
    }

    // 注册连接状态监听器
    private void addConnectionStateListener() {
        client.getConnectionStateListenable()
                .addListener((cli, newState)
                        -> Logs.ZK.warn("zk connection state changed new state={}, isConnected={}",
                        newState, newState.isConnected()));
    }

    // 本地缓存
    private void initLocalCache(String cachePath) throws Exception {
        cache = new TreeCache(client, cachePath);
        cache.start();
    }

    /**
     * 获取数据,先从本地获取，本地找不到，从远程获取
     *
     * @param key
     * @return
     */
    public String get(final String key) {
        if (null == cache) {
            return null;
        }
        ChildData data = cache.getCurrentData(key);
        if (null != data) {
            return null == data.getData() ? null : new String(data.getData(), Constants.UTF_8);
        }
        return getFromRemote(key);
    }

    /**
     * 从远程获取数据
     *
     * @param key
     * @return
     */
    public String getFromRemote(final String key) {
        try {
            return new String(client.getData().forPath(key), Constants.UTF_8);
        } catch (Exception ex) {
            Logs.ZK.error("getFromRemote:{}", key, ex);
            return null;
        }
    }

    /**
     * 获取子节点
     *
     * @param key
     * @return
     */
    public List<String> getChildrenKeys(final String key) {
        try {
            List<String> result = client.getChildren().forPath(key);
            Collections.sort(result, (o1, o2) -> o2.compareTo(o1));
            return result;
        } catch (Exception ex) {
            Logs.ZK.error("getChildrenKeys:{}", key, ex);
            return Collections.emptyList();
        }
    }

    /**
     * 判断路径是否存在
     *
     * @param key
     * @return
     */
    public boolean isExisted(final String key) {
        try {
            return null != client.checkExists().forPath(key);
        } catch (Exception ex) {
            Logs.ZK.error("isExisted:{}", key, ex);
            return false;
        }
    }

    /**
     * 持久化数据
     *
     * @param key
     * @param value
     */
    public void registerPersist(final String key, final String value) {
        try {
            if (isExisted(key)) {
                update(key, value);
            } else {
                client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(key, value.getBytes());
            }
        } catch (Exception ex) {
            Logs.ZK.error("persist:{},{}", key, value, ex);
            throw new ZKException(ex);
        }
    }

    /**
     * 更新数据
     *
     * @param key
     * @param value
     */
    public void update(final String key, final String value) {
        try {
            /*TransactionOp op = client.transactionOp();
            client.transaction().forOperations(
                    op.check().forPath(key),
                    op.setData().forPath(key, value.getBytes(Constants.UTF_8))
            );*/
            client.inTransaction().check().forPath(key).and().setData().forPath(key, value.getBytes(Constants.UTF_8)).and().commit();
        } catch (Exception ex) {
            Logs.ZK.error("update:{},{}", key, value, ex);
            throw new ZKException(ex);
        }
    }

    /**
     * 注册临时数据
     *
     * @param key
     * @param value
     */
    public void registerEphemeral(final String key, final String value) {
        try {
            if (isExisted(key)) {
                client.delete().deletingChildrenIfNeeded().forPath(key);
            }
            client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(key, value.getBytes(Constants.UTF_8));
        } catch (Exception ex) {
            Logs.ZK.error("persistEphemeral:{},{}", key, value, ex);
            throw new ZKException(ex);
        }
    }

    /**
     * 注册临时顺序数据
     *
     * @param key
     */
    public void registerEphemeralSequential(final String key, final String value) {
        try {
            client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL_SEQUENTIAL).forPath(key, value.getBytes());
        } catch (Exception ex) {
            Logs.ZK.error("persistEphemeralSequential:{},{}", key, value, ex);
            throw new ZKException(ex);
        }
    }

    /**
     * 注册临时顺序数据
     *
     * @param key
     */
    public void registerEphemeralSequential(final String key) {
        try {
            client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL_SEQUENTIAL).forPath(key);
        } catch (Exception ex) {
            Logs.ZK.error("persistEphemeralSequential:{}", key, ex);
            throw new ZKException(ex);
        }
    }

    /**
     * 删除数据
     *
     * @param key
     */
    public void remove(final String key) {
        try {
            client.delete().deletingChildrenIfNeeded().forPath(key);
        } catch (Exception ex) {
            Logs.ZK.error("removeAndClose:{}", key, ex);
            throw new ZKException(ex);
        }
    }

    public void registerListener(ZKNodeCacheWatcher listener) {
        cache.getListenable().addListener(listener);
    }

    public ZKConfig getZKConfig() {
        return zkConfig;
    }
}
