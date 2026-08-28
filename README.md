# Mnemosyne

> Mnemosyne (mnɛːmosýːnɛː) is the Greek god of memory. "Mnemosyne" is derived from the same source as the word mnemonic, that being the Greek word mnēmē, which means "remembrance, memory"

This library deduplicates signals received from external systems, by remembering which ones have already been processed.

It is based on three concepts:

- `id`: The unique identifier of the signal
- `processorId`: The unique identifier of the system processing the signal
- `Memoized`: The type of the value produced by processing the signal, which is stored and handed back to duplicates

It works across multiple nodes sharing the same `processorId`. The persistence is based on [DynamoDb](https://aws.amazon.com/dynamodb/) and its strong consistency write capability. The same concept can be applied to [Apache Cassandra](http://cassandra.apache.org/) or any other similar database that provides these two features:

- Strong consistency writes
- Upsert with the previous record values returned

## How to configure it

A `processorId` needs to be assigned to each instance of this library. It will uniquely identify the processor. If two services have the same `processorId` it likely means they are two instances of the same service.

We need to know the max amount of time the process will take (`maxProcessingTime` in the config). Any process that takes more than this amount of time will be considered dead.

## How does it work

It is based on the two phase commit strategy. It records when the processor starts to process a signal and when it completes it. It provides a `protect` method that wraps the effect of signal processing to guarantee that it will happen only once for each `processorId`:

```scala
trait Mnemosyne[F[_], Id, ProcessorId, Memoized] {
  def tryStartProcess(id: Id): F[Outcome[F, Memoized]]
  def protect(id: Id, fa: F[Memoized]): F[Memoized]
}
```

`protect` returns the value the effect produced, not `Unit`. On a first attempt it runs `fa` and stores the result; on a duplicate it runs nothing and returns the stored result. That matters whenever the effect produces something the caller still needs after deduplication kicks in — the message id an email provider hands back, for instance: the duplicate must not send the email again, but it does still need that id.

If you want the outcome rather than the value, `tryStartProcess` exposes it directly:

- `Outcome.New(completeProcess)`: nothing has run yet. Do the work, then hand the result to `completeProcess` so it is stored.
- `Outcome.Duplicate(value)`: it has run before, and `value` is what it produced.

Use `Memoized = Unit` if the effect has no result worth keeping.

The DynamoDb table has this structure:

- `id`: S - The unique identifier of the signal
- `processorId`: S - The unique identifier of the processor
- `startedAt`: N - The datetime the signal has started to be processed
- `completedAt`: N - The datetime when the signal has been completed
- `expiresOn`: N - The datetime when the signal process will expires
- `memoized`: The value produced by the process, encoded according to the `Memoized` type. Absent until the process completes

Each time a processor with a given `processorId` attempt to process a signal identified by `id`, it updates or writes on the table a record with `id`, `processorId`, `startedAt`. If the record with given `id` and `processorId` was already present, its value is returned to the library otherwise nothing is returned. After the process has run successfully, the library marks it as completed by storing the `completedAt`, `expiresOn` and `memoized` fields.

The `expiresOn` allows to clean up old data and re-run duplicate after some time.

When the library attempts to start a process, these scenarios can happen:

1) The signal has never been processed previously (not previous record found)
2) The signal has been already processed previously (`completedAt` is present)
3) The signal has timeout processing (`completedAt` is absent and `startedAt` + `processingTime` is in the past)
4) The signal is still being processed (`completedAt` is absent and `startedAt` + `processingTime` is in the future)

In cases (1) and (3) the library allows the signal to be processed. In case (2) it does not, as the signal has already been handled — it returns the memoized value instead. In case (4) it waits for the other attempt to either complete or time out before deciding.
