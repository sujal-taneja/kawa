package dev.kawa.examples;

/**
 * The payload of one job: make a thumbnail of this image at this width.
 * Any type Jackson can turn into JSON works as a payload.
 */
public record Thumbnail(String image, int width) {
}
