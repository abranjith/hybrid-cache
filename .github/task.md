# Java HybridCache Implementation — Master Task List

## Phase 0: Analysis & Planning
- [x] Deep analysis of C# codebase (~3000 lines, 20+ files)
- [x] Deep analysis of Java codebase (~2200 lines, 22 files)
- [x] Gap analysis and bug identification (8 critical bugs, 7 missing components)
- [x] Research: Virtual Threads vs CompletableFuture for API design
- [x] Write implementation plan
- [ ] Get user approval on plan

### Design Decisions (Approved)
- **Public API**: Synchronous (returns `T`, not `CompletableFuture<T>`). Virtual threads handle blocking internally.
- **CancellationToken**: Lightweight class wrapping `AtomicBoolean`
- **ByteArrayPool**: Dedicated `ByteArrayPool` for primitive `byte[]` (Option A)

## Phase 1: Fix Foundational Bugs & API Design
- [x] 1.0 Create `ByteArrayPool` (primitive `byte[]` pooling)
- [x] 1.1 Fix [BufferChunk](file:///e:/Cs.Projects/Cloned_Forked/dotnet%20aspnetcore%20main%20src-Caching_Hybrid_src/Internal/BufferChunk.cs#45-55) — `byte[]`, fix [doNotReturnToPool()](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/internal/BufferChunk.java#80-89) mutation
- [x] 1.2 Fix [IDistributedCache](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/abstractions/IDistributedCache.java#8-36) — `byte[]`
- [x] 1.3 Fix [IHybridCacheSerializer](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/abstractions/IHybridCacheSerializer.java#10-27) & [IHybridCacheSerializerFactory](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/abstractions/IHybridCacheSerializerFactory.java#6-14) signatures
- [x] 1.4 Fix [StampedeKey](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/internal/StampedeKey.java#9-81) & [HybridCacheEntryOptions](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/internal/HybridCacheEntryOptions.java#11-57) — `EnumSet<HybridCacheEntryFlags>`
- [x] 1.5 Fix [IMemoryCache](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/abstractions/IMemoryCache.java#5-26) — add expiration-aware [set()](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/hybrid/DefaultHybridCache.java#40-44) overload
- [x] 1.6 Create `CancellationToken`
- [x] 1.7 Redesign [HybridCache](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/abstractions/HybridCache.java#11-106) public API — sync + `CancellationToken`

## Phase 2: Core Missing Components
- [x] 2.1 Implement [TagSet](file:///e:/Cs.Projects/Cloned_Forked/dotnet%20aspnetcore%20main%20src-Caching_Hybrid_src/Internal/TagSet.cs#15-239)
- [x] 2.2 Implement [HybridCachePayload](file:///e:/Cs.Projects/Cloned_Forked/dotnet%20aspnetcore%20main%20src-Caching_Hybrid_src/Internal/HybridCachePayload.cs#12-422) (binary wire format)
- [ ] 2.3 Implement tag invalidation system (skipped per instructions)
- [x] 2.4 Implement partitioned sync locks
- [x] 2.5 Implement stampede coordination

## Phase 3: Complete DefaultHybridCache
- [ ] 3.1 Wire up [getOrCreate](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/hybrid/DefaultHybridCache.java#35-39) — full L1→stampede→L2→factory pipeline
- [ ] 3.2 Wire up [set](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/hybrid/DefaultHybridCache.java#40-44)
- [ ] 3.3 Wire up [remove](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/abstractions/IMemoryCache.java#20-25) and [removeByTag](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/hybrid/DefaultHybridCache.java#50-54)
- [ ] 3.4 Implement serializer resolution & caching
- [ ] 3.5 Update `StampedeState.backgroundFetch()` — full L2 pipeline

## Phase 4: CacheItem TagSet Integration
- [x] 4.1 Add [TagSet](file:///e:/Cs.Projects/Cloned_Forked/dotnet%20aspnetcore%20main%20src-Caching_Hybrid_src/Internal/TagSet.cs#27-32) to [BaseCacheItem](file:///e:/java/hybrid-cache/src/main/java/dev/shetty/hybrid/BaseCacheItem.java#10-103)

## Phase 5: Tests & Verification
- [ ] `ByteArrayPoolTest`
- [ ] `TagSetTest`
- [ ] `BufferChunkTest`
- [ ] `StampedeKeyTest`
- [ ] `HybridCachePayloadTest` (round-trip)
- [ ] `CancellationTokenTest`
- [ ] `DefaultHybridCacheTest` (L1 only, L1+L2, stampede, tags)
