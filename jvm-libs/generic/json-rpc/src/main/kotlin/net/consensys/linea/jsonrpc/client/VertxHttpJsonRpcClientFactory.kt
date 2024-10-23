package net.consensys.linea.jsonrpc.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import io.micrometer.core.instrument.MeterRegistry
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpVersion
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.net.URL
import java.util.function.Predicate
import java.util.function.Supplier

class VertxHttpJsonRpcClientFactory(
  private val vertx: Vertx,
  private val meterRegistry: MeterRegistry,
  private val requestResponseLogLevel: Level = Level.TRACE,
  private val failuresLogLevel: Level = Level.DEBUG,
  private val requestIdSupplier: Supplier<Any> = SequentialIdSupplier.singleton
) {
  fun create(
    endpoint: URL,
    maxPoolSize: Int? = null,
    httpVersion: HttpVersion? = null,
    requestObjectMapper: ObjectMapper = objectMapper,
    responseObjectMapper: ObjectMapper = objectMapper,
    log: Logger = LogManager.getLogger(VertxHttpJsonRpcClient::class.java),
    requestResponseLogLevel: Level = this.requestResponseLogLevel,
    failuresLogLevel: Level = this.failuresLogLevel
  ): VertxHttpJsonRpcClient {
    val clientOptions =
      HttpClientOptions()
        .setKeepAlive(true)
        .setDefaultHost(endpoint.host)
        .setDefaultPort(endpoint.port)
    maxPoolSize?.let(clientOptions::setMaxPoolSize)
    httpVersion?.let(clientOptions::setProtocolVersion)
    val httpClient = vertx.createHttpClient(clientOptions)
    return VertxHttpJsonRpcClient(
      httpClient,
      endpoint,
      meterRegistry,
      log = log,
      requestParamsObjectMapper = requestObjectMapper,
      responseObjectMapper = responseObjectMapper,
      requestResponseLogLevel = requestResponseLogLevel,
      failuresLogLevel = failuresLogLevel
    )
  }

  fun createWithLoadBalancing(
    endpoints: Set<URL>,
    maxInflightRequestsPerClient: UInt,
    httpVersion: HttpVersion? = null,
    requestObjectMapper: ObjectMapper = objectMapper,
    responseObjectMapper: ObjectMapper = objectMapper,
    log: Logger = LogManager.getLogger(VertxHttpJsonRpcClient::class.java),
    requestResponseLogLevel: Level = this.requestResponseLogLevel,
    failuresLogLevel: Level = this.failuresLogLevel
  ): JsonRpcClient {
    return LoadBalancingJsonRpcClient.create(
      endpoints.map { endpoint ->
        create(
          endpoint = endpoint,
          maxPoolSize = maxInflightRequestsPerClient.toInt(),
          httpVersion = httpVersion,
          requestObjectMapper = requestObjectMapper,
          responseObjectMapper = responseObjectMapper,
          log = log,
          requestResponseLogLevel = requestResponseLogLevel,
          failuresLogLevel = failuresLogLevel
        )
      },
      maxInflightRequestsPerClient
    )
  }

  fun createWithRetries(
    endpoint: URL,
    maxPoolSize: Int? = null,
    retryConfig: RequestRetryConfig,
    methodsToRetry: Set<String>,
    httpVersion: HttpVersion? = null,
    requestObjectMapper: ObjectMapper = objectMapper,
    responseObjectMapper: ObjectMapper = objectMapper,
    log: Logger = LogManager.getLogger(VertxHttpJsonRpcClient::class.java),
    requestResponseLogLevel: Level = this.requestResponseLogLevel,
    failuresLogLevel: Level = this.failuresLogLevel
  ): JsonRpcClient {
    val rpcClient = create(
      endpoint = endpoint,
      maxPoolSize = maxPoolSize,
      httpVersion = httpVersion,
      requestObjectMapper = requestObjectMapper,
      responseObjectMapper = responseObjectMapper,
      log = log,
      requestResponseLogLevel = requestResponseLogLevel,
      failuresLogLevel = failuresLogLevel
    )

    return JsonRpcRequestRetryer(
      this.vertx,
      rpcClient,
      config = JsonRpcRequestRetryer.Config(
        methodsToRetry = methodsToRetry,
        requestRetry = retryConfig
      ),
      log = log
    )
  }

  fun createWithLoadBalancingAndRetries(
    vertx: Vertx,
    endpoints: Set<URL>,
    maxInflightRequestsPerClient: UInt,
    retryConfig: RequestRetryConfig,
    methodsToRetry: Set<String>,
    httpVersion: HttpVersion? = null,
    requestObjectMapper: ObjectMapper = objectMapper,
    responseObjectMapper: ObjectMapper = objectMapper,
    log: Logger = LogManager.getLogger(VertxHttpJsonRpcClient::class.java),
    requestResponseLogLevel: Level = this.requestResponseLogLevel,
    failuresLogLevel: Level = this.failuresLogLevel
  ): JsonRpcClient {
    val loadBalancingClient = createWithLoadBalancing(
      endpoints = endpoints,
      maxInflightRequestsPerClient = maxInflightRequestsPerClient,
      httpVersion = httpVersion,
      requestObjectMapper = requestObjectMapper,
      responseObjectMapper = responseObjectMapper,
      log = log,
      requestResponseLogLevel = requestResponseLogLevel,
      failuresLogLevel = failuresLogLevel
    )

    return JsonRpcRequestRetryer(
      vertx,
      loadBalancingClient,
      config = JsonRpcRequestRetryer.Config(
        methodsToRetry = methodsToRetry,
        requestRetry = retryConfig
      ),
      requestObjectMapper = requestObjectMapper,
      failuresLogLevel = failuresLogLevel,
      log = log
    )
  }

  fun createV2(
    endpoints: Set<URL>,
    maxInflightRequestsPerClient: UInt? = null,
    retryConfig: RequestRetryConfig,
    httpVersion: HttpVersion? = null,
    requestObjectMapper: ObjectMapper = objectMapper,
    responseObjectMapper: ObjectMapper = objectMapper,
    shallRetryRequestsClientBasePredicate: Predicate<Result<Any?, Throwable>> = Predicate { it is Err },
    log: Logger = LogManager.getLogger(VertxHttpJsonRpcClient::class.java),
    requestResponseLogLevel: Level = this.requestResponseLogLevel,
    failuresLogLevel: Level = this.failuresLogLevel
  ): JsonRpcV2Client {
    assert(endpoints.isNotEmpty()) { "endpoints set is empty " }
    // create base client
    return if (maxInflightRequestsPerClient != null || endpoints.size > 1) {
      createWithLoadBalancing(
        endpoints = endpoints,
        maxInflightRequestsPerClient = maxInflightRequestsPerClient!!,
        httpVersion = httpVersion,
        requestObjectMapper = requestObjectMapper,
        responseObjectMapper = responseObjectMapper,
        log = log,
        requestResponseLogLevel = requestResponseLogLevel,
        failuresLogLevel = failuresLogLevel
      )
    } else {
      create(
        endpoint = endpoints.first(),
        httpVersion = httpVersion,
        requestObjectMapper = requestObjectMapper,
        responseObjectMapper = responseObjectMapper,
        log = log,
        requestResponseLogLevel = requestResponseLogLevel,
        failuresLogLevel = failuresLogLevel
      )
    }.let {
      // Wrap the client with a retryer
      JsonRpcRequestRetryerV2(
        vertx = vertx,
        delegate = it,
        requestRetry = retryConfig,
        requestObjectMapper = requestObjectMapper,
        shallRetryRequestsClientBasePredicate = shallRetryRequestsClientBasePredicate,
        failuresLogLevel = failuresLogLevel,
        log = log
      )
    }.let {
      // Wrap the client with a v2 client helper
      JsonRpcV2ClientImpl(
        delegate = it,
        idSupplier = requestIdSupplier
      )
    }
  }
}
