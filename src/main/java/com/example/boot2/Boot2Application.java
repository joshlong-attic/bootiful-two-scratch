package com.example.boot2;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Collections;
import java.util.function.Consumer;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

@SpringBootApplication
public class Boot2Application {

	@Bean
	WebSocketHandlerAdapter webSocketHandlerAdapter() {
		return new WebSocketHandlerAdapter();
	}

	@Bean
	WebSocketHandler webSocketHandler() {
		return session ->
				session.send(
						Flux.generate((Consumer<SynchronousSink<WebSocketMessage>>) sink -> sink.next(session.textMessage("Hello " + System.currentTimeMillis())))
								.delayElements(Duration.ofSeconds(1)));
	}

	@Bean
	HandlerMapping simpleUrlHandlerMapping() {
		SimpleUrlHandlerMapping simpleUrlHandlerMapping = new SimpleUrlHandlerMapping();
		simpleUrlHandlerMapping.setUrlMap(Collections.singletonMap("/ws/hello", webSocketHandler()));
		simpleUrlHandlerMapping.setOrder(10);
		return simpleUrlHandlerMapping;
	}


	@Bean
	RouterFunction<ServerResponse> routes(PersonRepository repo) {
		return RouterFunctions
				.route(GET("/people"), r -> ServerResponse.ok().body(repo.findAll(), Person.class))
				.andRoute(GET("/message/{name}"), r -> ServerResponse.ok().body(Flux.just("Hello, " + r.pathVariable("name") + "!"), String.class));
	}

	@Bean
	MapReactiveUserDetailsService authentication() {
		return new MapReactiveUserDetailsService(
				User
						.withDefaultPasswordEncoder()
						.username("jw")
						.roles("ADMIN", "USER")
						.password("pw")
						.build(),
				User
						.withDefaultPasswordEncoder()
						.username("jl")
						.roles("USER")
						.password("pw")
						.build()
		);
	}

	@Bean
	SecurityWebFilterChain authorization(ServerHttpSecurity http) {

		//@formatter:off
		return
				http
						.csrf().disable()
						.httpBasic()
						.and()
						.authorizeExchange()
						.pathMatchers("/message/{name}")
								.access((authentication, ctx) ->
									Mono.just(new AuthorizationDecision(ctx.getVariables().get("name").equals("rwinch"))))
						.pathMatchers("/people")
							.authenticated()
						.anyExchange().permitAll()
						.and()
						.build();
		//@formatter:on
	}

	public static void main(String[] args) {
		SpringApplication.run(Boot2Application.class, args);
	}
}

@RestController
class DelayingRestController {

	private final MeterRegistry meterRegistry;

	DelayingRestController(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	@GetMapping("/block")
	String block() throws Exception {

		// incremental updates to the number: +1, -1
		this.meterRegistry.counter("block-calls").increment();

		// all at once, on-demand (how much RAM does the computer have?)
		this.meterRegistry.gauge("ram", Math.random() * 1000);

		this.meterRegistry.timer("duration-of-block").record(() -> {
			try {
				Thread.sleep(10 * 1000);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		});

		return "blocking..";
	}
}

/*
@RestController
class PersonRestController {

	private final PersonRepository personRepository;

	PersonRestController(PersonRepository personRepository) {
		this.personRepository = personRepository;
	}

	@GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE, value = "/sse")
	Publisher<String> sse() {
		return
				Flux
						.generate((Consumer<SynchronousSink<String>>) sink -> sink.next("Hello " + System.currentTimeMillis()))
						.delayElements(Duration.ofSeconds(1));
	}

	@GetMapping("/people")
	Publisher<Person> people() {
		return this.personRepository.findAll();
	}
}
*/

@Component
class DataInitializer implements ApplicationRunner {

	private final PersonRepository personRepository;

	DataInitializer(PersonRepository personRepository) {
		this.personRepository = personRepository;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {

		Scheduler singleExecutor = Schedulers.elastic();

		this.personRepository
				.deleteAll()
				.subscribeOn(singleExecutor)
				.thenMany(new Wrapper<>(Flux
						.just("Jim", "Josh", "Bob", "Tasha", "Andrew", "Kenny", "Mark", "Paul", "Nate", "Mario", "Yeonjin")
						.map(name -> new Person(null, name))
						.flatMap(this.personRepository::save)))
				.subscribeOn(singleExecutor)
				.thenMany(new Wrapper<>(this.personRepository.findAll()))
				.subscribeOn(singleExecutor)
				.subscribe(System.out::println);
	}

	public static class WrapperSubscriber<T> implements Subscriber<T> {

		private final Subscriber<T> delegate;

		public WrapperSubscriber(Subscriber<T> delegate) {
			this.delegate = delegate;
		}

		private Log log = LogFactory.getLog(getClass());

		private void debug() {
			log.debug("current thread: " + Thread.currentThread().getName());
		}

		@Override
		public void onSubscribe(Subscription s) {
			this.delegate.onSubscribe(s);
			debug();
		}

		@Override
		public void onNext(T t) {
			this.delegate.onNext(t);
			debug();
		}

		@Override
		public void onError(Throwable t) {
			this.delegate.onError(t);
			debug();
		}

		@Override
		public void onComplete() {
			this.delegate.onComplete();
			debug();
		}
	}

	public static class Wrapper<T> implements Publisher<T> {
		private final Publisher<T> delegate;

		public Wrapper(Publisher<T> delegate) {
			this.delegate = delegate;
		}

		@Override
		public void subscribe(Subscriber<? super T> s) {
			this.delegate.subscribe(new WrapperSubscriber<>(s));
		}
	}
}

interface PersonRepository extends ReactiveMongoRepository<Person, String> {

	Flux<Person> findByFirst(String name);
}

@Document
@Data
@AllArgsConstructor
@NoArgsConstructor
class Person {

	@Id
	private String id;
	private String first;
}
