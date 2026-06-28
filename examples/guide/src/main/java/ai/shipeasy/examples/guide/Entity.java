package ai.shipeasy.examples.guide;

/**
 * A single Shipeasy entity row rendered as a card on the guide page.
 *
 * <p>Everything here is presentation data. {@code value} / {@code meta} are the
 * <em>placeholder</em> outputs the live SDK call would produce; {@code code} is
 * the SDK call itself, shown verbatim on the page next to the {@code // TODO}
 * blocks in {@link GuideController}.
 *
 * @param typeLabel    uppercase pill label, e.g. "FEATURE FLAG"
 * @param key          the entity key, rendered in mono as the card title
 * @param value        the placeholder result, rendered in the accent value pill
 * @param meta         faint one-line note under the description (reason, props, …)
 * @param description  one-sentence "what this is"
 * @param code         the real SDK call, shown as a code block
 * @param accent       hex accent colour for the pills, e.g. "#34d399"
 */
public record Entity(
        String typeLabel,
        String key,
        String value,
        String meta,
        String description,
        String code,
        String accent
) {
}
