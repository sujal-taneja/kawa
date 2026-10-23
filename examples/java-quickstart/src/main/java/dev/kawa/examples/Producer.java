package dev.kawa.examples;

import dev.kawa.client.EnqueueOptions;
import dev.kawa.client.KawaClient;

import java.time.Duration;
import java.util.UUID;

/**
 * The "your app" side: adds thumbnail jobs and returns immediately. Nothing here does the work.
 */
public class Producer {

    public static void main(String[] args) {
        KawaClient kawa = KawaClient.create(System.getenv().getOrDefault("KAWA_URL", "http://localhost:8080"));

        for (String image : new String[] {"beach.jpg", "city.jpg", "forest.jpg", "mountain.jpg"}) {
            kawa.enqueue("thumbnails", new Thumbnail(image, 200));
            System.out.println("enqueued  " + image);
        }

        kawa.enqueue("thumbnails", new Thumbnail("profile-photo.jpg", 64),
                EnqueueOptions.builder().priority(9).build());
        System.out.println("enqueued  profile-photo.jpg  (priority 9: jumps the queue)");

        kawa.enqueue("thumbnails", new Thumbnail("corrupted.jpg", 200),
                EnqueueOptions.builder().maxAttempts(2).build());
        System.out.println("enqueued  corrupted.jpg  (always fails: retried once, then DEAD)");

        kawa.enqueue("thumbnails", new Thumbnail("banner.jpg", 800),
                EnqueueOptions.builder().delay(Duration.ofSeconds(15)).build());
        System.out.println("enqueued  banner.jpg  (delayed: runs in 15 seconds)");

        // a retried request with the same idempotency key gets the original job back
        EnqueueOptions keyed = EnqueueOptions.builder().idempotencyKey("avatar-ada").build();
        UUID first = kawa.enqueue("thumbnails", new Thumbnail("avatar-ada.jpg", 64), keyed);
        UUID retry = kawa.enqueue("thumbnails", new Thumbnail("avatar-ada.jpg", 64), keyed);
        System.out.println("enqueued  avatar-ada.jpg twice with one Idempotency-Key -> "
                + (first.equals(retry) ? "one job" : "two jobs?!"));
    }
}
