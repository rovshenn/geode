/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.cache30;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import org.junit.Test;

import org.apache.geode.SystemFailure;
import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.CacheLoaderException;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.LoaderHelper;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.TimeoutException;
import org.apache.geode.test.dunit.Assert;
import org.apache.geode.test.dunit.AsyncInvocation;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.LogWriterUtils;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.ThreadUtils;
import org.apache.geode.test.dunit.VM;

/**
 * This class tests the functionality of a cache {@link Region region} that has a scope of
 * {@link Scope#GLOBAL global}.
 *
 * @since GemFire 3.0
 */

public class GlobalRegionDUnitTest extends MultiVMRegionTestCase {

  /**
   * Returns region attributes for a <code>GLOBAL</code> region
   */
  @Override
  protected RegionAttributes getRegionAttributes() {
    AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.GLOBAL);
    factory.setConcurrencyChecksEnabled(false);
    factory.setDataPolicy(DataPolicy.PRELOADED);
    return factory.create();
  }

  ////////////////////// Test Methods //////////////////////

  /**
   * Tests the compatibility of creating certain kinds of subregions of a local region.
   *
   * @see Region#createSubregion
   */
  @Test
  public void testIncompatibleSubregions() throws CacheException, InterruptedException {

    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);

    // Scope.DISTRIBUTED_NO_ACK is illegal if there is any other cache
    // in the distributed system that has the same region with
    // Scope.GLOBAL

    final String name = this.getUniqueName() + "-GLOBAL";
    vm0.invoke(new SerializableRunnable("Create GLOBAL Region") {
      @Override
      public void run() {
        try {
          createRegion(name, "INCOMPATIBLE_ROOT", getRegionAttributes());
        } catch (CacheException ex) {
          Assert.fail("While creating GLOBAL region", ex);
        }
        assertTrue(getRootRegion("INCOMPATIBLE_ROOT").getAttributes().getScope().isGlobal());
      }
    });

    vm1.invoke(new SerializableRunnable("Create NO ACK Region") {
      @Override
      public void run() {
        try {
          AttributesFactory factory = new AttributesFactory(getRegionAttributes());
          factory.setScope(Scope.DISTRIBUTED_NO_ACK);
          try {
            assertNull(getRootRegion("INCOMPATIBLE_ROOT"));
            createRegion(name, "INCOMPATIBLE_ROOT", factory.create());
            fail("Should have thrown an IllegalStateException");
          } catch (IllegalStateException ex) {
            // pass...
            // assertNull(getRootRegion());
          }

        } catch (CacheException ex) {
          Assert.fail("While creating GLOBAL Region", ex);
        }
      }
    });

    vm1.invoke(new SerializableRunnable("Create ACK Region") {
      @Override
      public void run() {
        try {
          AttributesFactory factory = new AttributesFactory(getRegionAttributes());
          factory.setScope(Scope.DISTRIBUTED_ACK);
          try {
            // assertNull(getRootRegion()); // This should be null, but is not
            RegionAttributes attrs = factory.create();
            createRootRegion("INCOMPATIBLE_ROOT", attrs);
            fail("Should have thrown an IllegalStateException");

            createRegion(name, "INCOMPATIBLE_ROOT", factory.create());
            fail("Should have thrown an IllegalStateException");

          } catch (IllegalStateException ex) {
            // pass...
            assertNull(getRootRegion());
          }

        } catch (CacheException ex) {
          Assert.fail("While creating GLOBAL Region", ex);
        }
      }
    });
  }

  /**
   * Tests that a value in a remote cache will be fetched by <code>netSearch</code> and that no
   * loaders are invoked.
   */
  @Test
  public void testRemoteFetch() throws CacheException {
    assertTrue(getRegionAttributes().getScope().isDistributed());

    final String name = this.getUniqueName();
    final Object key = "KEY";
    final Object value = "VALUE";

    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);

    SerializableRunnable create = new CacheSerializableRunnable("Create Region") {
      @Override
      public void run2() throws CacheException {
        Region region = createRegion(name);
        setLoader(new TestCacheLoader<Object, Object>() {
          @Override
          public Object load2(LoaderHelper<Object, Object> helper) throws CacheLoaderException {

            fail("Should not be invoked");
            return null;
          }
        });
        region.getAttributesMutator().setCacheLoader(loader());
      }
    };

    vm0.invoke(create);
    vm0.invoke(new CacheSerializableRunnable("Put") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        region.put(key, value);
        assertFalse(loader().wasInvoked());
      }
    });

    vm1.invoke(create);

    vm1.invoke(new CacheSerializableRunnable("Get") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        assertEquals(value, region.get(key));
        assertFalse(loader().wasInvoked());
      }
    });
  }

  /**
   * Tests that a bunch of threads in a bunch of VMs all atomically incrementing the value of an
   * entry get the right value.
   */
  @Test
  public void testSynchronousIncrements() throws InterruptedException {

    // getCache().setLockTimeout(getCache().getLockTimeout() * 2);

    final String name = this.getUniqueName();
    final Object key = "KEY";
    // final Object value = "VALUE";

    Host host = Host.getHost(0);
    final int vmCount = host.getVMCount();
    final int threadsPerVM = 3;
    final int incrementsPerThread = 10;

    SerializableRunnable create = new CacheSerializableRunnable("Create region") {
      @Override
      public void run2() throws CacheException {
        createRegion(name);
        Region region = getRootRegion().getSubregion(name);
        region.put(key, new Integer(0));
      }
    };

    VM vm0 = host.getVM(0);
    vm0.invoke(create);
    for (int i = 1; i < vmCount; i++) {
      VM vm = host.getVM(i);
      vm.invoke(create);
    }

    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      fail("interrupted");
    }

    SerializableRunnable increment = new CacheSerializableRunnable("Start Threads and increment") {
      @Override
      public void run2() throws CacheException {
        final ThreadGroup group = new ThreadGroup("Incrementors") {
          @Override
          public void uncaughtException(Thread t, Throwable e) {
            if (e instanceof VirtualMachineError) {
              SystemFailure.setFailure((VirtualMachineError) e); // don't throw
            }
            String s = "Uncaught exception in thread " + t;
            Assert.fail(s, e);
          }
        };

        Thread[] threads = new Thread[threadsPerVM];
        for (int i = 0; i < threadsPerVM; i++) {
          Thread thread = new Thread(group, new Runnable() {
            @Override
            public void run() {
              try {
                LogWriterUtils.getLogWriter().info("testSynchronousIncrements." + this);
                final Random rand = new Random(System.identityHashCode(this));
                try {
                  Region region = getRootRegion().getSubregion(name);
                  for (int j = 0; j < incrementsPerThread; j++) {
                    Thread.sleep(rand.nextInt(30) + 30);

                    Lock lock = region.getDistributedLock(key);
                    assertTrue(lock.tryLock(-1, TimeUnit.MILLISECONDS));

                    Integer value = (Integer) region.get(key);
                    Integer oldValue = value;
                    if (value == null) {
                      value = new Integer(1);

                    } else {
                      Integer v = value;
                      value = new Integer(v.intValue() + 1);
                    }

                    assertEquals(oldValue, region.get(key));
                    region.put(key, value);
                    assertEquals(value, region.get(key));

                    LogWriterUtils.getLogWriter()
                        .info("testSynchronousIncrements." + this + ": " + key + " -> " + value);
                    lock.unlock();
                  }

                } catch (InterruptedException ex) {
                  Assert.fail("While incrementing", ex);
                } catch (Exception ex) {
                  Assert.fail("While incrementing", ex);
                }
              } catch (VirtualMachineError e) {
                SystemFailure.initiateFailure(e);
                throw e;
              } catch (Throwable t) {
                LogWriterUtils.getLogWriter()
                    .info("testSynchronousIncrements." + this + " caught Throwable", t);
              }
            }
          }, "Incrementer " + i);
          threads[i] = thread;
          thread.start();
        }

        for (int i = 0; i < threads.length; i++) {
          ThreadUtils.join(threads[i], 30 * 1000);
        }
      }
    };

    AsyncInvocation[] invokes = new AsyncInvocation[vmCount];
    for (int i = 0; i < vmCount; i++) {
      invokes[i] = host.getVM(i).invokeAsync(increment);
    }

    for (int i = 0; i < vmCount; i++) {
      ThreadUtils.join(invokes[i], 5 * 60 * 1000);
      if (invokes[i].exceptionOccurred()) {
        Assert.fail("invocation failed", invokes[i].getException());
      }
    }

    vm0.invoke(new CacheSerializableRunnable("Verify final value") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        Integer value = (Integer) region.get(key);
        assertNotNull(value);
        int expected = vmCount * threadsPerVM * incrementsPerThread;
        assertEquals(expected, value.intValue());
      }
    });
  }

  /**
   * Tests that {@link Region#put} and {@link Region#get} timeout when another VM holds the
   * distributed lock on the entry in question.
   */
  @Test
  public void testPutGetTimeout() {
    assertEquals(Scope.GLOBAL, getRegionAttributes().getScope());

    final String name = this.getUniqueName();
    final Object key = "KEY";
    final Object value = "VALUE";

    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);

    SerializableRunnable create = new CacheSerializableRunnable("Create Region") {
      @Override
      public void run2() throws CacheException {
        createRegion(name);
      }
    };

    vm0.invoke(create);

    vm1.invoke(create);

    vm0.invoke(new CacheSerializableRunnable("Lock entry") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        Lock lock = region.getDistributedLock(key);
        lock.lock();
      }
    });

    vm1.invoke(new CacheSerializableRunnable("Attempt get/put") {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();
        cache.setLockTimeout(1);
        cache.setSearchTimeout(1);
        Region region = getRootRegion().getSubregion(name);

        try {
          region.put(key, value);
          fail("Should have thrown a TimeoutException on put");

        } catch (TimeoutException ex) {
          // pass..
        }

        // With a loader, should try to lock and time out
        region.getAttributesMutator().setCacheLoader(new TestCacheLoader() {
          @Override
          public Object load2(LoaderHelper helper) {
            return null;
          }
        });
        try {
          region.get(key);
          fail("Should have thrown a TimeoutException on get");

        } catch (TimeoutException ex) {
          // pass..
        }

        // Without a loader, should succeed
        region.getAttributesMutator().setCacheLoader(null);
        region.get(key);
      }
    });

    vm0.invoke(new CacheSerializableRunnable("Unlock entry") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        Lock lock = region.getDistributedLock(key);
        lock.unlock();
      }
    });
  }

}
