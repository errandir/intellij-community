// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs.persistent.dev.blobstorage;

import com.intellij.util.io.PageCacheUtils;
import com.intellij.util.io.PagedFileStorageLockFree;
import org.jetbrains.annotations.NotNull;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.experimental.theories.Theories;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.Assume.assumeTrue;

/**
 *
 */
@RunWith(Theories.class)
public class StreamlinedBlobStorageOverLockFreePagedStorageTest
  extends StreamlinedBlobStorageTestBase<StreamlinedBlobStorageOverLockFreePagesStorage> {

  @BeforeClass
  public static void beforeClass() throws Exception {
    assumeTrue(
      "PageCacheUtils.LOCK_FREE_VFS_ENABLED must be true for this test to run",
      PageCacheUtils.LOCK_FREE_VFS_ENABLED
    );
  }

  public StreamlinedBlobStorageOverLockFreePagedStorageTest(final @NotNull Integer pageSize,
                                                            final @NotNull SpaceAllocationStrategy strategy) {
    super(pageSize, strategy);
  }


  @Override
  protected StreamlinedBlobStorageOverLockFreePagesStorage openStorage(final Path pathToStorage) throws IOException {
    final PagedFileStorageLockFree pagedStorage = new PagedFileStorageLockFree(
      pathToStorage,
      LOCK_CONTEXT,
      pageSize,
      true
    );
    return new StreamlinedBlobStorageOverLockFreePagesStorage(
      pagedStorage,
      allocationStrategy
    );
  }

  @Override
  public void tearDown() throws Exception {
    if (storage != null) {
      System.out.printf("Storage after test: %d records allocated, %d deleted, %d relocated, live records %.1f%% of total \n",
                        storage.recordsAllocated(),
                        storage.recordsDeleted(),
                        storage.recordsRelocated(),
                        storage.liveRecordsCount() * 100.0 / storage.recordsAllocated()
      );
      System.out.printf("                    %d bytes live payload, %d live capacity, live payload %.1f%% of total storage size \n",
                        storage.totalLiveRecordsPayloadBytes(),
                        storage.totalLiveRecordsCapacityBytes(),
                        storage.totalLiveRecordsPayloadBytes() * 100.0 / storage.sizeInBytes() //including _storage_ header
      );
    }
    super.tearDown();
  }
  
}