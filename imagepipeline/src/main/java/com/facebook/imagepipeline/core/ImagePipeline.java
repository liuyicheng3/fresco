/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.core;

import javax.annotation.concurrent.ThreadSafe;

import java.lang.Exception;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import android.net.Uri;

import com.facebook.cache.common.CacheKey;
import com.facebook.cache.disk.DiskStorageCache;
import com.facebook.common.internal.Objects;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.Supplier;
import com.facebook.common.references.CloseableReference;
import com.facebook.common.util.UriUtil;
import com.facebook.datasource.DataSource;
import com.facebook.datasource.DataSources;
import com.facebook.imagepipeline.cache.BitmapMemoryCacheKey;
import com.facebook.imagepipeline.cache.MemoryCache;
import com.facebook.imagepipeline.cache.CacheKeyFactory;
import com.facebook.imagepipeline.common.Priority;
import com.facebook.imagepipeline.datasource.CloseableProducerToDataSourceAdapter;
import com.facebook.imagepipeline.datasource.ProducerToDataSourceAdapter;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.memory.PooledByteBuffer;
import com.facebook.imagepipeline.producers.Producer;
import com.facebook.imagepipeline.producers.SettableProducerContext;
import com.facebook.imagepipeline.listener.ForwardingRequestListener;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.listener.RequestListener;

import com.android.internal.util.Predicate;

import com.facebook.imagepipeline.request.ImageRequestBuilder;

/**
 * The entry point for the image pipeline.
 */
@ThreadSafe
public class ImagePipeline {

  private static final CancellationException PREFETCH_EXCEPTION =
      new CancellationException("Prefetching is not enabled");

  private final ProducerSequenceFactory mProducerSequenceFactory;
  private final RequestListener mRequestListener;
  private final Supplier<Boolean> mIsPrefetchEnabledSupplier;
  private final MemoryCache<CacheKey, CloseableImage> mBitmapMemoryCache;
  private final MemoryCache<CacheKey, PooledByteBuffer> mEncodedMemoryCache;
  private final DiskStorageCache mMainDiskStorageCache;
  private final DiskStorageCache mSmallImageDiskStorageCache;
  private final CacheKeyFactory mCacheKeyFactory;

  private AtomicLong mIdCounter;

  public ImagePipeline(
      ProducerSequenceFactory producerSequenceFactory,
      Set<RequestListener> requestListeners,
      Supplier<Boolean> isPrefetchEnabledSupplier,
      MemoryCache<CacheKey, CloseableImage> bitmapMemoryCache,
      MemoryCache<CacheKey, PooledByteBuffer> encodedMemoryCache,
      DiskStorageCache mainDiskStorageCache,
      DiskStorageCache smallImageDiskStorageCache,
      CacheKeyFactory cacheKeyFactory) {
    mIdCounter = new AtomicLong();
    mProducerSequenceFactory = producerSequenceFactory;
    mRequestListener = new ForwardingRequestListener(requestListeners);
    mIsPrefetchEnabledSupplier = isPrefetchEnabledSupplier;
    mBitmapMemoryCache = bitmapMemoryCache;
    mEncodedMemoryCache = encodedMemoryCache;
    mMainDiskStorageCache = mainDiskStorageCache;
    mSmallImageDiskStorageCache = smallImageDiskStorageCache;
    mCacheKeyFactory = cacheKeyFactory;
  }

  /**
   * Generates unique id for RequestFuture.
   *
   * @return unique id
   */
  private String generateUniqueFutureId() {
    return String.valueOf(mIdCounter.getAndIncrement());
  }

  /**
   * Returns a DataSource supplier that will on get submit the request for execution and return a
   * DataSource representing the pending results of the task.
   *
   * @param imageRequest the request to submit (what to execute).
   * @param bitmapCacheOnly whether to only look for the image in the bitmap cache
   * @return a DataSource representing pending results and completion of the request
   */
  public Supplier<DataSource<CloseableReference<CloseableImage>>> getDataSourceSupplier(
      final ImageRequest imageRequest,
      final Object callerContext,
      final boolean bitmapCacheOnly) {
    return new Supplier<DataSource<CloseableReference<CloseableImage>>>() {
      @Override
      public DataSource<CloseableReference<CloseableImage>> get() {
        if (bitmapCacheOnly) {
          return fetchImageFromBitmapCache(imageRequest, callerContext);
        } else {
          return fetchDecodedImage(imageRequest, callerContext);
        }
      }

      @Override
      public String toString() {
        return Objects.toStringHelper(this)
            .add("uri", imageRequest.getSourceUri())
            .toString();
      }
    };
  }

  /**
   * Submits a request for bitmap cache lookup.
   *
   * @param imageRequest the request to submit
   * @return a DataSource representing the image
   */
  public DataSource<CloseableReference<CloseableImage>> fetchImageFromBitmapCache(
      ImageRequest imageRequest,
      Object callerContext) {
    try {
      Producer<CloseableReference<CloseableImage>> producerSequence =
          mProducerSequenceFactory.getDecodedImageProducerSequence(imageRequest);
      return submitFetchRequest(
          producerSequence,
          imageRequest,
          ImageRequest.RequestLevel.BITMAP_MEMORY_CACHE,
          callerContext);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  /**
   * Submits a request for execution and returns a DataSource representing the pending decoded
   * image(s).
   * <p>The returned DataSource must be closed once the client has finished with it.
   * @param imageRequest the request to submit
   * @return a DataSource representing the pending decoded image(s)
   */
  public DataSource<CloseableReference<CloseableImage>> fetchDecodedImage(
      ImageRequest imageRequest,
      Object callerContext) {
    try {
      Producer<CloseableReference<CloseableImage>> producerSequence =
          mProducerSequenceFactory.getDecodedImageProducerSequence(imageRequest);
      return submitFetchRequest(
          producerSequence,
          imageRequest,
          ImageRequest.RequestLevel.FULL_FETCH,
          callerContext);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  /**
   * Submits a request for execution and returns a DataSource representing the pending encoded
   * image(s).
   *
   * <p> The ResizeOptions in the imageRequest will be ignored for this fetch
   *
   * <p>The returned DataSource must be closed once the client has finished with it.
   *
   * @param imageRequest the request to submit
   * @return a DataSource representing the pending encoded image(s)
   */
  public DataSource<CloseableReference<PooledByteBuffer>> fetchEncodedImage(
      ImageRequest imageRequest,
      Object callerContext) {
    Preconditions.checkNotNull(imageRequest.getSourceUri());
    try {
      Producer<CloseableReference<PooledByteBuffer>> producerSequence =
          mProducerSequenceFactory.getEncodedImageProducerSequence(imageRequest);
      // The resize options are used to determine whether images are going to be downsampled during
      // decode or not. For the case where the image has to be downsampled and it's a local image it
      // will be kept as a FileInputStream until decoding instead of reading it in memory. Since
      // this method returns an encoded image, it should always be read into memory. Therefore, the
      // resize options are ignored to avoid treating the image as if it was to be downsampled
      // during decode.
      if (imageRequest.getResizeOptions() != null) {
        imageRequest = ImageRequestBuilder.fromRequest(imageRequest)
            .setResizeOptions(null)
            .build();
      }
      return submitFetchRequest(
          producerSequence,
          imageRequest,
          ImageRequest.RequestLevel.FULL_FETCH,
          callerContext);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  /**
   * Submits a request for prefetching to the bitmap cache.
   * @param imageRequest the request to submit
   * @return a DataSource that can safely be ignored.
   */
  public DataSource<Void> prefetchToBitmapCache(
      ImageRequest imageRequest,
      Object callerContext) {
    if (!mIsPrefetchEnabledSupplier.get()) {
      return DataSources.immediateFailedDataSource(PREFETCH_EXCEPTION);
    }
    try {
      Producer<Void> producerSequence =
          mProducerSequenceFactory.getDecodedImagePrefetchProducerSequence(imageRequest);
      return submitPrefetchRequest(
          producerSequence,
          imageRequest,
          ImageRequest.RequestLevel.FULL_FETCH,
          callerContext);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  /**
   * Submits a request for prefetching to the disk cache.
   * @param imageRequest the request to submit
   * @return a DataSource that can safely be ignored.
   */
  public DataSource<Void> prefetchToDiskCache(
      ImageRequest imageRequest,
      Object callerContext) {
    if (!mIsPrefetchEnabledSupplier.get()) {
      return DataSources.immediateFailedDataSource(PREFETCH_EXCEPTION);
    }
    try {
      Producer<Void> producerSequence =
          mProducerSequenceFactory.getEncodedImagePrefetchProducerSequence(imageRequest);
      return submitPrefetchRequest(
          producerSequence,
          imageRequest,
          ImageRequest.RequestLevel.FULL_FETCH,
          callerContext);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  /**
   * Removes all images with the specified {@link Uri} from memory cache.
   * @param uri The uri of the image to evict
   */
  public void evictFromMemoryCache(final Uri uri) {
    final String cacheKeySourceString = mCacheKeyFactory.getCacheKeySourceUri(uri).toString();
    Predicate<CacheKey> bitmapCachePredicate =
        new Predicate<CacheKey>() {
          @Override
          public boolean apply(CacheKey key) {
            if (key instanceof BitmapMemoryCacheKey) {
              return ((BitmapMemoryCacheKey) key).getSourceUriString().equals(cacheKeySourceString);
            }
            return false;
          }
        };
    mBitmapMemoryCache.removeAll(bitmapCachePredicate);

    Predicate<CacheKey> encodedCachePredicate =
        new Predicate<CacheKey>() {
          @Override
          public boolean apply(CacheKey key) {
            return key.toString().equals(cacheKeySourceString);
          }
        };
    mEncodedMemoryCache.removeAll(encodedCachePredicate);
  }

  /**
   * <p>If you have supplied your own cache key factory when configuring the pipeline, this method
   * may not work correctly. It will only work if the custom factory builds the cache key entirely
   * from the URI. If that is not the case, use {@link #evictFromDiskCache(ImageRequest)}.
   * @param uri The uri of the image to evict
   */
  public void evictFromDiskCache(final Uri uri) {
    final CacheKey cacheKey = mCacheKeyFactory.getEncodedCacheKey(ImageRequest.fromUri(uri));
    mMainDiskStorageCache.remove(cacheKey);
    mSmallImageDiskStorageCache.remove(cacheKey);
  }

  /**
   * Removes all images with the specified {@link Uri} from disk cache.
   *
   * @param imageRequest The imageRequest for the image to evict from disk cache
   */
  public void evictFromDiskCache(final ImageRequest imageRequest) {
    final CacheKey cacheKey = mCacheKeyFactory.getEncodedCacheKey(imageRequest);
    mMainDiskStorageCache.remove(cacheKey);
    mSmallImageDiskStorageCache.remove(cacheKey);
  }

  /**
   * <p>If you have supplied your own cache key factory when configuring the pipeline, this method
   * may not work correctly. It will only work if the custom factory builds the cache key entirely
   * from the URI. If that is not the case, use {@link #evictFromMemoryCache(Uri)} and
   * {@link #evictFromDiskCache(ImageRequest)} separately.
   * @param uri The uri of the image to evict
   */
  public void evictFromCache(final Uri uri) {
    evictFromMemoryCache(uri);
    evictFromDiskCache(uri);
  }

  /**
   * Clear the memory caches
   */
  public void clearMemoryCaches() {
    Predicate<CacheKey> allPredicate =
        new Predicate<CacheKey>() {
          @Override
          public boolean apply(CacheKey key) {
            return true;
          }
        };
    mBitmapMemoryCache.removeAll(allPredicate);
    mEncodedMemoryCache.removeAll(allPredicate);
  }

  /**
   * Clear disk caches
   */
  public void clearDiskCaches() {
    mMainDiskStorageCache.clearAll();
    mSmallImageDiskStorageCache.clearAll();
  }

  /**
   * Clear all the caches (memory and disk)
   */
  public void clearCaches() {
    clearMemoryCaches();
    clearDiskCaches();
  }

  private <T> DataSource<CloseableReference<T>> submitFetchRequest(
      Producer<CloseableReference<T>> producerSequence,
      ImageRequest imageRequest,
      ImageRequest.RequestLevel lowestPermittedRequestLevelOnSubmit,
      Object callerContext) {
    try {
      ImageRequest.RequestLevel lowestPermittedRequestLevel =
          ImageRequest.RequestLevel.getMax(
              imageRequest.getLowestPermittedRequestLevel(),
              lowestPermittedRequestLevelOnSubmit);
      SettableProducerContext settableProducerContext = new SettableProducerContext(
          imageRequest,
          generateUniqueFutureId(),
          mRequestListener,
          callerContext,
          lowestPermittedRequestLevel,
        /* isPrefetch */ false,
          imageRequest.getProgressiveRenderingEnabled() ||
              !UriUtil.isNetworkUri(imageRequest.getSourceUri()),
          imageRequest.getPriority());
        //请求是在这里面发出的 请继续看下去
      return CloseableProducerToDataSourceAdapter.create(
          producerSequence,
          settableProducerContext,
          mRequestListener);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  private DataSource<Void> submitPrefetchRequest(
      Producer<Void> producerSequence,
      ImageRequest imageRequest,
      ImageRequest.RequestLevel lowestPermittedRequestLevelOnSubmit,
      Object callerContext) {
    try {
      ImageRequest.RequestLevel lowestPermittedRequestLevel =
          ImageRequest.RequestLevel.getMax(
              imageRequest.getLowestPermittedRequestLevel(),
              lowestPermittedRequestLevelOnSubmit);
      SettableProducerContext settableProducerContext = new SettableProducerContext(
          imageRequest,
          generateUniqueFutureId(),
          mRequestListener,
          callerContext,
          lowestPermittedRequestLevel,
        /* isPrefetch */ true,
        /* isIntermediateResultExpected */ false,
          Priority.LOW);
      return ProducerToDataSourceAdapter.create(
          producerSequence,
          settableProducerContext,
          mRequestListener);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }
}
