package com.example.api

/**
 * Editorial shot grammar for a film storyboard. The cycle deliberately alternates
 * location, action, reaction, detail, relationship, POV and aftermath instead of
 * centering the lead character in every generated frame.
 */
data class CinematicShotDesign(
    val family: String,
    val storyboardInstruction: String,
    val imageDirective: String,
    val motionDirective: String
)

object CinematicShotPlanner {
    private val designs = listOf(
        CinematicShotDesign(
            family = "ESTABLISHING_WIDE",
            storyboardInstruction = "wide location-first establishing shot; landscape and architecture dominate, people absent or small and off-center",
            imageDirective = "wide establishing composition; the location and spatial geography occupy most of the frame; people, if present, remain small and off-center",
            motionDirective = "slow lateral reveal that introduces the location before emphasizing any person"
        ),
        CinematicShotDesign(
            family = "SPATIAL_MASTER",
            storyboardInstruction = "wide or medium-wide master with clear foreground, middle distance and background; stage action off-center",
            imageDirective = "wide master composition with clear foreground, middle distance and background; keep the main subject off-center and preserve readable geography",
            motionDirective = "measured tracking movement that follows the action through the space, not a push into a portrait"
        ),
        CinematicShotDesign(
            family = "REACTION",
            storyboardInstruction = "reaction on the actual ally, antagonist or witness in this beat; if none is present, use a meaningful environmental detail, not a hero close-up",
            imageDirective = "reaction framing on the actual secondary, antagonist or witness present in this beat; never substitute the protagonist; if none is present, frame a meaningful environmental detail",
            motionDirective = "brief restrained reaction movement or rack focus to the person or detail that changes the meaning of the beat"
        ),
        CinematicShotDesign(
            family = "INSERT_DETAIL",
            storyboardInstruction = "extreme close insert of a story-specific prop, hand, clue or texture; no face and no generic hero portrait",
            imageDirective = "cinematic insert / macro detail of the story-specific object, hand, clue or texture; exclude faces and generic hero portraits",
            motionDirective = "subtle macro drift or rack focus across the story-specific detail; avoid unnecessary body or face animation"
        ),
        CinematicShotDesign(
            family = "TWO_SHOT_RELATIONSHIP",
            storyboardInstruction = "balanced two-shot or over-the-shoulder showing both sides of the relationship and their distance in space",
            imageDirective = "balanced two-shot or over-the-shoulder composition showing both sides of the relationship and their spatial distance; do not isolate the protagonist",
            motionDirective = "gentle over-the-shoulder or lateral movement that preserves both characters and their eyelines"
        ),
        CinematicShotDesign(
            family = "SUBJECTIVE_POV",
            storyboardInstruction = "subjective point-of-view or obstructed foreground showing what the present character sees or fears; avoid a centered face",
            imageDirective = "subjective point-of-view through the eyes or position of a character actually present, with an obstructed foreground or visible threat; no centered portrait",
            motionDirective = "controlled subjective camera movement from the character's position, with natural human pace and no abrupt zoom"
        ),
        CinematicShotDesign(
            family = "LATERAL_ACTION",
            storyboardInstruction = "lateral tracking wide shot with readable full-body action and geography; no static head-and-shoulders framing",
            imageDirective = "lateral action composition with readable body movement, environment and direction of travel; avoid a static head-and-shoulders portrait",
            motionDirective = "lateral tracking that keeps the action readable in the environment and maintains screen direction"
        ),
        CinematicShotDesign(
            family = "AFTERMATH_NEGATIVE_SPACE",
            storyboardInstruction = "aftermath or transition with negative space, silhouette or empty environment carrying emotion; people distant or absent",
            imageDirective = "aftermath or transition composition with negative space, silhouette or empty environment carrying the emotion; people distant or absent",
            motionDirective = "quiet hold, slow pan or environmental movement that lets the aftermath breathe"
        )
    )

    fun forScene(sceneNumber: Int): CinematicShotDesign {
        require(sceneNumber >= 1) { "sceneNumber must be positive" }
        return designs[(sceneNumber - 1) % designs.size]
    }
}
