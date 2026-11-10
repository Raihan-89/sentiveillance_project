package com.raihan.sentiveillance_project.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Person-related detection, and the switches that keep vehicles out of the pipeline.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
@Getter
@Setter
public class People {
    /**
     * Discard detections classified as CAR, BUS, TRUCK or BIKE. Leave true - it is what
     * makes this a people-only pipeline. Setting it to false records whatever the body
     * detector reports, vehicles included.
     */
    private boolean humansOnly = true;
    /**
     * Confidence the body detector needs before reporting a box (SDK units).
     * Raise it if furniture or shadows are being tracked as people.
     */
    private int detectorThreshold = 50;
    /**
     * How many scales the object detector runs: ONE, TWO, THREE or FOUR.
     *
     * <p>This is the single control over <em>how far away</em> a person is still detected,
     * and it trades directly against latency. Measured on this machine (CPU, 2160x3840,
     * same clip, cap at 0.33s):
     * <pre>
     *   ONE    4 subjects    8 recognised   peak lag  2.4s
     *   TWO    5 subjects   14-16 recognised   peak lag  5-7s
     *   THREE  5 subjects   16 recognised   peak lag 10.7s
     *   FOUR   7-8 subjects  7 recognised   peak lag 17-19s
     * </pre>
     * Subject count and recognition count are the reproducible signals; the smallest box
     * the detector reports is not - it came out 180px, 233px and 233px across three runs
     * at TWO, because it depends on which frames happened to be sampled. The one place
     * that figure was stable is FOUR, which reported an 82px-wide person in two
     * independent runs against 233px everywhere else: that setting really does see about
     * three times further, at the price of an overlay that is no longer a live view.
     *
     * <p>TWO is the chosen balance: it consistently finds one more subject than ONE and
     * close to double the identifications, while the dashboard stays usable.
     *
     * <p>The way out of the trade is fewer pixels per frame, not a different setting -
     * halve the source resolution and every row above gets roughly twice as fast.
     */
    private String detectorScaling = "TWO";
    /**
     * Minimum seconds between frames handed to the detector - a frame-rate cap, and the
     * difference between an overlay that keeps up and one that drifts further behind the
     * longer the source runs. 0 leaves the SDK default (0.17s) alone.
     *
     * <p>Measured peak lag over the same clip at {@code detectorScaling=ONE}: 0.17s gave
     * ~4900ms, 0.33s gave ~2270ms, 0.50s gave ~810ms. Both enrolled people were still
     * recognised at every setting; what shrinks is the number of samples per visit.
     *
     * <p>Note that it also costs range: at {@code detectorScaling=TWO} the smallest person
     * detected was 180px wide with this at 0.33s, but 233px with it at 0.50s. Fewer frames
     * examined means a subject is first caught when they are already closer. 0.33s is the
     * point where latency improves without measurably shortening range.
     */
    private float detectorMinFrameTimeSeconds = 0.33f;
    /** Extract upper/lower clothing colour per tracked person. */
    private boolean detectClothingDetails = true;
    /** Classify a face/head covering - mask, hat, hood. */
    private boolean detectFaceAndHeadCoverings = true;
    /**
     * Estimate an age group (child / teenager / adult / senior). Off by default: it
     * loads another model and costs CPU per track.
     */
    private boolean estimateAgeGroup = false;
}