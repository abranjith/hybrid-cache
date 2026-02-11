# hybrid-cache

hybrid-cache is a java implementation of Hybrid Cache implementation in C#

## Features

* hybrid-cache supports multi level caching with memory and distributed cache.
* configurable serialization
* stampede protection

## Tech Decisions

* doesn't require any external dependencies
* doesn't implement any specific distributed cache or memory cache, but provides interfaces to implement your own.
* uses java.util.concurrent for concurrency
* don't overwrite existing code or classes, but extends them.
* use java's closest equivalent of C# features in case exact equivalent is not available.
* ignore logic around cancellation tokens, as they are not applicable in java.
* don't implement any logic around TagSet or TagInvalidation
* ignore Debug.Assert & logging statements

FOR COMPLETE IMPLEMENTATION DETAILS, REFER implementation_plan.md
