# Kiroku layout rubric

Rubric version: **1**.

Reviewers use this rubric to score every layout change. It is fixed so that scores from different reviewers and
different review cycles can be compared. Only the owner changes it, in a pull request of its own that raises the
version number. Every score states the rubric version it was given under.

The rubric judges what a user sees. Code quality is reviewed separately.

## 1. What is reviewed

A layout task changes one or more screens. **A changed screen** is any screen whose composables, strings or fixtures
the task touched. A change to the theme or to a shared component (`docs/ARCHITECTURE.md` section 8) makes every
existing screen a changed screen, but a screen that changed *only* in that way gets the reduced set below.

For each changed screen the reviewer looks at, in `app/build/outputs/roborazzi/<screen>/`:

- every case in the five standard variants (`en-light`, `en-dark`, `ru-light`, `ru-dark`, `ru-stress`), and the
  content case also in `ru-large` (font scale 2.0) and `en-alt` (a second seed colour);
- for each of these, the viewport image `<case>__<variant>.png` and, where the content is taller than the screen, the
  full-height image `<case>__<variant>__full.png`. Everything below the first screen is judged from the `__full`
  image. `__full` images exist for the variants that `docs/ARCHITECTURE.md` section 11.2 names in its line
  "`__full` images are written for"; that is all variants unless the line says otherwise;
- for the content case, each horizontal strip `<case>__<variant>__strip-<n>.png`, stitched so that every card
  shows. Cards beyond the first screen width are judged from it. Magenta in it means the strip changed height
  while scrolling, which breaks the rule that all cards of a strip have one height;
- `NOT_SHOWN.txt`, when the screen has no loading, empty or error case: the stated reason is part of what is
  scored, and a state missing without a reason is a missing image;
- `<case>__bounds.png` of the content case, which shows component bounds, for alignment;
- the result of the automatic accessibility check, which runs on every case, in the task's test output;
- `app/build/outputs/roborazzi/RENDERED_AT`, which must name the commit under review and must not say "dirty";
- the screen's budget in `docs/ARCHITECTURE.md` section 9, the design system in section 8, and `docs/GLOSSARY.md`;
- the calibration set in `docs/calibration/`, once it exists (roadmap step M1-15): screenshots with the owner's
  scores, read before the first cycle to align the scale.

**The reduced set.** For a screen that changed only through the theme or a shared component, the reviewer looks at
the content case in all seven variants, with its `__full` images and the bounds image, and scores the screen from those.
On such a screen only issues caused by the task's change are deducted. Issues that existed before go under
"Optional", with the screen named. The task's own screens always get the full set.

If an image is missing or `RENDERED_AT` does not match, the reviewer renders again before scoring, all changed
screens in one command, because a render deletes the images of every screen it does not render:
`tools/gw :app:testDebugUnitTest -Proborazzi.test.record=true --tests '*<Screen>Screenshots*'`, with one
`--tests '*<Screen>Screenshots*'` for each changed screen. When every screen is changed (the theme or a shared
component), the command is `tools/gw --kind check :app:recordRoborazziDebug`. A reviewer never scores from a
description or from the code.

## 2. Definitions

- **Base variants:** the four variants at font scale 1.0 on the 411 dp screen: `en-light`, `en-dark`, `ru-light`,
  `ru-dark`. The **standard variants** are these four and `ru-stress`.
- **Logical line:** one text item of a budget, such as the title, "line 2" or a caption. It is not one line on
  screen. How many lines on screen it may take depends on its line cap.
- **Line cap:** the number of lines on screen that a budget allows a text item ("line cap two"). An item with a cap
  may fill it in any variant, never exceeds it, and ends in an ellipsis when its text is longer. An item for which
  the budget says "no line cap" may take any number of lines. An item for which the budget says nothing is treated
  by its kind:
  - A **label or data line** (a row line, a caption, a fact, a value, a button, tab or chip label, a top app bar
    title) takes one line in the base variants and may wrap to a second only in `ru-stress` and `ru-large`.
  - A **sentence** (the sign-in sentence, the unofficial notice, a state message, the text of a dialog, snackbar or
    banner, a setting's explanation, an explanation in the status panel) has no line cap.

  Neither ever has an ellipsis.
- **Groups of controls** (chips, buttons, steppers) are not logical lines. A group may wrap to further lines unless
  the budget says it may not.
- **Ellipsis:** allowed only on an item with a line cap, and never on a number or a button label.
- **Clipped:** any text or control that is cut off, overlaps something, or leaves its container, other than an allowed
  ellipsis.
- **Content from MAL:** titles and alternative titles, synopses, user names, and proper names such as studios and
  producers. Everything else the user reads is interface text and must be translated, including status, media type,
  season, source, rating, relation type and genre names.

Example: the list row's budget is "at most three logical lines": the title (line cap two), line 2 and line 3. A row
with a two-line title, line 2 and line 3 shows four lines on screen and is within the budget. A row with a fourth
text item is not.

## 3. Criteria and scoring

Each criterion starts at 10 and loses points for each issue found in any variant.

**One issue** is one defect with one cause. The same defect repeated in several rows, cases or variants because of
one component, one string or one layout rule is one issue. Two defects with different causes are two issues, even
when they are of the same kind. An issue is deducted once, at its largest size in any variant, under the single
criterion that describes it best: never under two criteria, and never once per variant or per case. The deduction
names the cause.

**In `ru-large` only**, a wrap, an orphaned word or uneven row heights that follow from the font scale alone are at
most Minor: at font scale 2.0 some wraps cannot be avoided, and they are not design defects. Clipping, the
automatic failures and every other defect count at full size there too.

| Issue size | Deduction | Meaning |
|---|---|---|
| Minor | −0.5 | Visible only on a close look; a user would not notice it |
| Noticeable | −1.5 | A user would notice it, but the screen still works as intended |
| Structural | −3 | The screen's arrangement itself is wrong for this criterion |

A criterion cannot go below 0. Every deduction is listed with the file, the place and the cause.

Examples of each size, for the three criteria where the size is most often disputed. The calibration set shows more.

| Criterion | Minor (−0.5) | Noticeable (−1.5) | Structural (−3) |
|---|---|---|---|
| 1 Budget and noise | A divider between rows that are already separated by spacing; a unit repeated in two adjacent values | An element that repeats line 2 (a progress bar under `12 / 24`); an icon in front of every row that says nothing the row's text does not | A second block that restates the first (a summary card above a list of the same facts); decoration that takes more room than the content |
| 2 Hierarchy and focus | A caption one type role heavier than its neighbours; a secondary line at the same emphasis as the line it supports | A secondary button styled like the primary one; a status shown by a coloured dot alone | Two competing primary buttons; no element that reads as the screen's subject |
| 4 Spacing and alignment | A 4 dp mismatch between two section gaps; one icon 2 dp off the text baseline | Unequal gaps between the lines of a row; a column of values whose left edges do not line up | Groups not separated more than their members anywhere on the screen; content touching the screen edge |

| # | Criterion | Weight | What counts as an issue |
|---|---|---|---|
| 1 | **Information budget and noise** | 25 | An element that answers no question the user has on this screen; the same fact stated twice; decoration. (Line 3 of a list row and the "to watch" figure in the airing block are intended highlights, not repeats.) Exceeding a limit or omitting a Must-show element is not a deduction but an automatic failure. |
| 2 | **Hierarchy and focus** | 20 | No single primary element; the primary action is not the most prominent control; secondary elements compete; importance carried by colour alone; reading order unclear. |
| 3 | **Material 3 fidelity** | 15 | A component used for something it is not meant for; a non-default shape or elevation; a type role outside the allowed list; an element that a Material 3 user would not recognise. (`StatBar` and `EpisodeStepper` as defined in the design system are allowed.) |
| 4 | **Spacing, alignment and rhythm** | 15 | Edges that do not line up; unequal gaps between equal things; groups not separated more than their members; crowding. Judged from the screenshots and the bounds image. (That code uses the spacing tokens is checked in code review, not here.) |
| 5 | **Robustness across variants** | 10 | An awkward wrap, an orphaned word, an unbalanced line, a wrong Russian case or plural form, a term that contradicts the glossary, dark theme less legible than light, `en-alt` showing a colour that did not follow the theme. |
| 6 | **States** | 5 | A loading, empty or error state that does not use the shared component, has a vague message, or lacks its one useful action. |
| 7 | **Touch and legibility** | 5 | Low contrast in either theme; text that reads as too small; controls too close together to hit reliably. (The 48 dp minimum and missing labels are machine-checked.) |
| 8 | **Consistency** | 5 | The same thing looking, being worded or being placed differently than on other screens. |

**Screen score** = Σ (criterion score × weight) / 100. It is reported rounded to one decimal, and compared with the
bar of section 4 before rounding.

**The component gallery** is not a screen: criteria 1, 2, 6 and 8 do not apply. It is scored on criterion 3 (weight
35), 4 (35), 5 (20) and 7 (10), with the same 8.5 bar and the same three deduction limits of section 4 (Noticeable,
Structural, Minor), and without the floors on criteria 1 and 2.

## 4. Passing

A screen that passes has, across all its variants, at most one issue a user would notice and a few they would not.
An experienced Material Design reviewer would ship it without asking for changes. The weighted score alone does not
guarantee that: one Noticeable issue under each of the eight criteria still averages 8.5, a Structural issue
under a criterion of weight 5 costs 0.15, and thirteen Minor issues under a criterion of weight 15 cost under one
point. The pass rule therefore counts deductions as well.

**A screen passes** for a reviewer when all six conditions hold:

1. the screen score, before rounding, is at least 8.5;
2. criteria 1 and 2 are each at least 8;
3. the screen has **at most one Noticeable deduction**, counted over all criteria together;
4. the screen has **no Structural deduction** under any criterion;
5. the screen has **at most six Minor deductions**, counted over all criteria together;
6. there is no automatic failure (section 5).

**A task passes** when every changed screen passes for both reviewers.

- The floor of condition 2 exists because noise and hierarchy are the reason this project exists. With conditions 3
  and 5 it limits the minor issues there: each of the two criteria may carry four Minor deductions, or one
  Noticeable and one Minor, within the six of the whole screen.
- A good screen typically carries two to four Minor deductions, so six leaves room without admitting a ragged one.
- The screen score remains the measure of how close a screen is and of the trend between cycles.
- The two reviewers score independently and do not see each other's scores before submitting.
- The score of a task is the lower of the two reviewers' scores of its weakest screen.
- At most three fix cycles. If the task still does not pass, it goes to the owner with both reviews.

## 5. Automatic failures

Any one of these fails the screen regardless of its score. The reviewer names the file and the place.

1. Text or a control is clipped (section 2) in any variant.
2. A visible interface string is hard-coded or untranslated in a `ru-*` variant. Only content from MAL (section 2) is
   exempt.
3. The screen exceeds a limit of its budget, shows something the budget lists as not on the screen, uses more
   logical lines than allowed, or a text item takes more lines on screen than its line cap allows (section 2).
4. An element the budget lists under **Must show** is absent in a case where it applies.
5. A home-made control where a stock Material 3 component exists, or a component that is not in the design system's
   list.
6. A colour that is not a theme role, a gradient, a background image, or text drawn over an image.
7. Anything on the design system's "not allowed anywhere" list: a toast, a shimmer placeholder, a decorative card, a
   chip that does nothing, an auto-scrolling carousel, a second-by-second countdown, more than one floating action
   button.
8. The automatic accessibility check reports a touch target under 48 dp or an unlabeled control.
9. Text that cannot be read against its background in either theme.
10. Content hidden under the status bar or the gesture bar, or a last list item that cannot be scrolled clear of the
    gesture bar in the `__full` image.
11. A required image is missing: a case, a variant, the bounds image, or a `__full` image of a scrolling case in a
    variant for which section 1 requires one.
12. On the title page: the header, the list block and the airing block do not all fit above the fold in the four
    base variants, for a title of up to two lines.

## 6. The review report

Each reviewer returns exactly this, once per changed screen:

```
Rubric version: 1
Commit: <hash from RENDERED_AT>
Screen: <name>            Cycle: <1|2|3>

Automatic failures: none | <number>: <file> – <what and where>

| # | Criterion             | Weight | Score | Deductions (size: file, place, cause)       |
|---|-----------------------|--------|-------|---------------------------------------------|
| 1 | Information budget    | 25     |       |                                             |
| 2 | Hierarchy and focus   | 20     |       |                                             |
| 3 | Material 3 fidelity   | 15     |       |                                             |
| 4 | Spacing and alignment | 15     |       |                                             |
| 5 | Robustness            | 10     |       |                                             |
| 6 | States                | 5      |       |                                             |
| 7 | Touch and legibility  | 5      |       |                                             |
| 8 | Consistency           | 5      |       |                                             |

Screen score: <x.x> (before rounding: <x.xxx>)    Criteria 1 and 2 both at least 8: yes | no
Noticeable deductions: <n> (at most 1)    Structural deductions: <n> (must be 0)
Minor deductions: <n> (at most 6)    Verdict: PASS | FAIL

Required changes (each one concrete and checkable in a screenshot):
1. ...
```

- "Required changes" lists only what is needed to pass. Other suggestions go under a separate heading "Optional".
- In cycles 2 and 3 the reviewer first marks each required change of the previous cycle as done or not done, then
  scores the whole screen again from the new images. A score may go down if a fix broke something else.
- A reviewer who believes the budget itself is wrong says so under "Budget feedback" and still scores against the
  budget as written.
