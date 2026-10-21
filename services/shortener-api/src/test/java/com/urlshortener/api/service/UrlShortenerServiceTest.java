package com.urlshortener.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.urlshortener.api.cache.RedisRing;
import com.urlshortener.api.cache.UrlCache;
import com.urlshortener.api.config.AppProperties;
import com.urlshortener.api.domain.ShortUrl;
import com.urlshortener.api.id.IdGenerator;
import com.urlshortener.api.repository.ShortUrlRepository;
import com.urlshortener.api.validation.UrlValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-20T22:00:00Z");
  private static final String OWNER = "k_owner";

  @Mock ShortUrlRepository repository;
  @Mock UrlCache cache;
  @Mock RedisRing ring;
  @Mock IdGenerator ids;

  private UrlShortenerService service;

  @BeforeEach
  void setUp() {
    AppProperties props =
        new AppProperties(
            "http://sho.rt",
            List.of("k"),
            null,
            null,
            null,
            null,
            null,
            new AppProperties.Url(2048, List.of("localhost")));
    service =
        new UrlShortenerService(
            repository,
            cache,
            ring,
            ids,
            new UrlValidator(props),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new SimpleMeterRegistry());
  }

  @Test
  void createsNewLinkWithGeneratedCodeAndWritesThroughCache() {
    when(repository.findByUrlHash(anyString())).thenReturn(Optional.empty());
    when(ids.nextId()).thenReturn(371085501244903424L); // Base62 "RPZgLXaGtU"
    when(ring.shardFor("url:RPZgLXaGtU")).thenReturn(1);

    UrlShortenerService.CreateResult r =
        service.create(
            OWNER, "https://Example.com/a", Optional.empty(), Optional.of(Duration.ofHours(1)));

    assertThat(r.created()).isTrue();
    assertThat(r.url().shortCode()).isEqualTo("RPZgLXaGtU");
    assertThat(r.url().longUrl()).isEqualTo("https://example.com/a");
    assertThat(r.url().expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    assertThat(r.url().shardId()).isEqualTo(1);

    ArgumentCaptor<ShortUrl> saved = ArgumentCaptor.forClass(ShortUrl.class);
    verify(repository).insert(saved.capture());
    assertThat(saved.getValue().ownerId()).isEqualTo(OWNER);
    verify(cache).put("RPZgLXaGtU", "https://example.com/a", NOW.plus(Duration.ofHours(1)));
  }

  @Test
  void createIsIdempotentForSameOwnerAndUrl() {
    ShortUrl existing = link("abcd123", "https://example.com/a", null);
    when(repository.findByUrlHash(UrlShortenerService.urlHash(OWNER, "https://example.com/a")))
        .thenReturn(Optional.of(existing));

    UrlShortenerService.CreateResult r =
        service.create(OWNER, "https://example.com/a", Optional.empty(), Optional.empty());

    assertThat(r.created()).isFalse();
    assertThat(r.url()).isSameAs(existing);
    verify(repository, never()).insert(any());
    verify(ids, never()).nextId();
  }

  @Test
  void customAliasBypassesIdempotencyAndGenerator() {
    when(ring.shardFor(anyString())).thenReturn(0);

    UrlShortenerService.CreateResult r =
        service.create(OWNER, "https://example.com/b", Optional.of("launch2026"), Optional.empty());

    assertThat(r.created()).isTrue();
    assertThat(r.url().shortCode()).isEqualTo("launch2026");
    verify(repository, never()).findByUrlHash(anyString());
    verify(ids, never()).nextId();
  }

  @Test
  void takenAliasSurfacesAsConflict() {
    when(ring.shardFor(anyString())).thenReturn(0);
    doThrow(new ShortUrlRepository.CodeAlreadyExistsException("taken1"))
        .when(repository)
        .insert(any());

    assertThatThrownBy(
            () ->
                service.create(
                    OWNER, "https://example.com/c", Optional.of("taken1"), Optional.empty()))
        .isInstanceOf(UrlShortenerService.AliasTakenException.class);
    verify(cache, never()).put(anyString(), anyString(), any());
  }

  @Test
  void invalidAliasIsRejectedBeforeAnyIo() {
    assertThatThrownBy(
            () ->
                service.create(OWNER, "https://example.com/c", Optional.of("ab"), Optional.empty()))
        .isInstanceOf(UrlValidator.InvalidUrlException.class);
    verify(repository, never()).insert(any());
  }

  @Test
  void retriesOnGeneratedCodeCollision() {
    when(repository.findByUrlHash(anyString())).thenReturn(Optional.empty());
    when(ids.nextId()).thenReturn(1000L, 1001L);
    when(ring.shardFor(anyString())).thenReturn(0);
    doThrow(new ShortUrlRepository.CodeAlreadyExistsException("G8"))
        .doNothing()
        .when(repository)
        .insert(any());

    UrlShortenerService.CreateResult r =
        service.create(OWNER, "https://example.com/d", Optional.empty(), Optional.empty());

    assertThat(r.created()).isTrue();
    assertThat(r.url().shortCode()).isEqualTo("G9"); // 1001 in Base62; 1000 ("G8") collided
  }

  @Test
  void resolveReturnsCacheHitWithoutTouchingRepository() {
    when(cache.get("abcd123")).thenReturn(new UrlCache.Lookup(Optional.of("https://x.y/"), false));

    assertThat(service.resolve("abcd123")).contains("https://x.y/");
    verify(repository, never()).findByCode(anyString());
  }

  @Test
  void resolveHonoursNegativeCache() {
    when(cache.get("abcd123")).thenReturn(new UrlCache.Lookup(Optional.empty(), true));

    assertThat(service.resolve("abcd123")).isEmpty();
    verify(repository, never()).findByCode(anyString());
  }

  @Test
  void resolveFallsBackToRepositoryAndPopulatesCache() {
    when(cache.get("abcd123")).thenReturn(new UrlCache.Lookup(Optional.empty(), false));
    when(repository.findByCode("abcd123"))
        .thenReturn(Optional.of(link("abcd123", "https://x.y/", null)));

    assertThat(service.resolve("abcd123")).contains("https://x.y/");
    verify(cache).put("abcd123", "https://x.y/", null);
  }

  @Test
  void resolveWritesNegativeEntryForUnknownOrDeletedCodes() {
    when(cache.get("abcd123")).thenReturn(new UrlCache.Lookup(Optional.empty(), false));
    when(repository.findByCode("abcd123")).thenReturn(Optional.empty());

    assertThat(service.resolve("abcd123")).isEmpty();
    verify(cache).putNegative("abcd123");
  }

  @Test
  void resolveRejectsMalformedCodesWithoutIo() {
    assertThat(service.resolve("not valid!")).isEmpty();
    verify(cache, never()).get(anyString());
  }

  @Test
  void expiredLinksAreNotServed() {
    when(cache.get("abcd123")).thenReturn(new UrlCache.Lookup(Optional.empty(), false));
    when(repository.findByCode("abcd123"))
        .thenReturn(Optional.of(link("abcd123", "https://x.y/", Instant.now().minusSeconds(5))));

    assertThat(service.resolve("abcd123")).isEmpty();
    assertThatThrownBy(() -> service.get("abcd123"))
        .isInstanceOf(UrlShortenerService.NotFoundException.class);
  }

  @Test
  void deleteTombstonesAndEvicts() {
    when(repository.softDelete("abcd123", OWNER)).thenReturn(true);

    service.delete(OWNER, "abcd123");

    verify(cache).evict("abcd123");
  }

  @Test
  void deleteOfUnknownOrForeignLinkIsNotFound() {
    when(repository.softDelete(eq("abcd123"), anyString())).thenReturn(false);

    assertThatThrownBy(() -> service.delete("someone-else", "abcd123"))
        .isInstanceOf(UrlShortenerService.NotFoundException.class);
    verify(cache, never()).evict(anyString());
  }

  private static ShortUrl link(String code, String url, Instant expires) {
    return new ShortUrl(
        code,
        url,
        UrlShortenerService.urlHash(OWNER, url),
        OWNER,
        NOW,
        expires,
        0,
        ShortUrl.Status.ACTIVE,
        0);
  }
}
