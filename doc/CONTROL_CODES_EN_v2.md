# Brave Fencer Musashi English Control Code Notes

Working notes for the English text-editing pipeline.

These notes are based on in-game tests and the current English workbook dump. They are not final documentation. Treat unknown codes carefully and test on a disposable patched image first.

---

## Spreadsheet/importer behavior

The importer rebuilds each sentence block from the spreadsheet in row order.

For each row in an edited sentence block:

```text
append column D controls
then append column F edit if F is not blank
otherwise append column E original text
```

So the effective structure is:

```text
D + (F if edited, else E)
```

Important rules:

- Column D controls are not remembered automatically. They are written because they are present in the spreadsheet.
- If you change column D but leave all column F cells blank in that sentence block, the importer currently ignores that block.
- To force a control-code-only experiment to write, put unchanged original text into column F on one text row in the same sentence block.
- If you add control codes in column F, they are added after the existing column D controls for that row.
- To replace an existing control, change column D. To add an extra inline control, put it in column F.

---

## Colors

Color tags use the form:

```text
[cX]
```

These encode as the `01 XX` color control.

Confirmed/observed:

| Tag | Behavior |
|---|---|
| `[c0]` | invisible / background / avoid |
| `[c1]` | default gray / reset |
| `[c2]` | official emphasis color, red/pink |
| `[c3]` | magenta |
| `[c4]` | yellow |
| `[c5]` | red/coral |
| `[c6]` | blue/lavender |
| `[c7]` | gray-blue |
| `[c8]` | gray |
| `[c9]` | red |
| `[cA]` | green |
| `[cB]` | yellow |
| `[cC]` | blue |
| `[cD]` | magenta |
| `[cE]` | cyan |
| `[cF]` | white |

Practical use:

```text
[c1] = reset/default
[c2] = normal official emphasis
[c3]-[cF] = alternate colors
[c0] = avoid unless invisible text is desired
```

---

## Basic layout controls

| Tag | Working meaning |
|---|---|
| `[br]` | line break |
| `[wt]` | manual wait |
| `[new]` | new page / clear textbox |
| `[box2]` | changes textbox style to normal when combined with `[new]` |
| `[box6]` | changes textbox style to spiky/yelling when combined with `[new]` |

Notes:

- `[box2]` and `[box6]` may show no obvious effect unless paired with `[new]` or used in the right textbox context.
- `[br]` must remain `[br]` in the dump/import path. It is raw byte `0A`, not a normal ASCII space.

---

## Timed/cutscene dialogue controls

| Tag | Working meaning |
|---|---|
| `[15]` | auto-advance after timed/spoken text; generally used in cutscenes |
| `[16]` | wait for manual input / manual skip at the end |
| `[18]` | beginning of a name tag or sign |
| `[19]` | end of a name tag or sign; returns to `[15]` style/state |
| `[14xx00]` | duration/speed for timed text when used in the right structure |

Confirmed auto-advance pattern:

```text
[19][14xx00]Timed spoken line[15]
```

Confirmed manual-advance pattern:

```text
[19][14xx00]Timed spoken line[16]
```

Observed timing examples:

| Tag | Behavior |
|---|---|
| `[140000]` | fast / very short |
| `[143C00]` | medium |
| `[147800]` | slow |

The timing appears to use the second byte in `[14xx00]`.

Use this form for timing edits:

```text
[141000]
[143C00]
[147800]
[14A000]
```

Avoid assuming this form is equivalent:

```text
[140010]
[14003C]
[140078]
```

`[1400xx]` appears to have different/unclear behavior and may stop autoskipping.

---

## End controls

Common tags:

```text
[0300]
[0301]
[030A]
[0314]
[031E]
```

Current observation:

- Testing `[0300]`, `[0301]`, and `[030A]` showed no obvious visible change in the tested context.

Working guess:

- These are end/return/flow controls for dialogue or event script behavior.
- Preserve them unless deliberately testing.

---

## Voice / actor / scene controls

| Tag family | Working guess |
|---|---|
| `[05]` | next speaker/message indicator; commonly starts the next speaker's textbox |
| `[0Bxxxxxx]` | voice-line ID / voice clip trigger |
| `[10xxxx]` | actor animation/action cue |
| `[11xxxx]` | actor animation/action cue variant, related action, or paired end action |
| `[02xxxx]` | textbox style/inset + portrait/actor index |

Notes:

- `[0Bxxxxxx]` appears to be line-specific and often occurs only once or twice. This strongly suggests voice/event references.
- `[10xxxx]` and `[11xxxx]` should be preserved unless testing actor animation behavior.
- `[05]` is very common at the beginning of speaker/message sections.

---

## `[02xxxx]` textbox / portrait / actor control

Working model:

```text
[02 LS PP]
```

Where:

```text
02 = textbox/speaker command
L  = textbox inset / border pull-in value
S  = textbox style
PP = portrait/actor image index or pointer-table index
```

This is best understood as two bytes after the `02` command:

```text
[02][LS][PP]
```

### `L` nibble: textbox inset / border pull-in

Tested form:

```text
[02x000]
```

Observed behavior:

- This changes the textbox border opposite the portrait.
- If the portrait is on the right, the left border pulls inward.
- If the portrait is on the left, the right border pulls inward.
- The code does not seem to decide portrait side by itself; portrait side appears to come from actor/portrait setup or scene state.
- `0` through `5` appear usable.
- `6` and `7` can break/freeze.
- `8` and onward produce random/unsafe sizes.

Working note:

```text
L0-L5 = usable inset values
L6-L7 = unsafe / can freeze
L8-LF = unpredictable / avoid
```

### `S` nibble: textbox style

Tested form:

```text
[020x00]
```

Observed style map:

| S value | Behavior |
|---|---|
| `0` | no textbox |
| `1` | square scroll/info box |
| `2` | normal speaking textbox |
| `3` | least spiky textbox |
| `4` | more spiky textbox |
| `5` | next level of spiky textbox |
| `6` | spikiest textbox |
| `7` | spikiest textbox |
| `8` | no textbox |
| `9` | square scroll/info box |
| `A` | normal speaking textbox |
| `B` | least spiky textbox |
| `C` | more spiky textbox |
| `D` | no textbox |
| `E` | square scroll/info box |
| `F` | unknown / needs testing |

Working interpretation:

- The engine probably does not use all bits of this nibble directly.
- Some values alias to the same textbox style.
- Safe normal choices:
  - `S1` = square/info/scroll style
  - `S2` = normal speaking box
  - `S3-S7` = increasing spiky/yelling styles
- Avoid `S0`, `S8`, and `SD` unless no textbox is intended.

### `PP` byte: portrait/actor image index

Tested forms:

```text
[02000x]
[0200x1]
```

Observed behavior:

- This appears to select the portrait image or actor portrait entry.
- `[02000x]` changes the low nibble of the portrait/actor index.
- `[0200x1]` changes the high nibble of the same portrait/actor index.
- Many edited values show garbage portraits or a blank portrait area.
- This suggests the full final byte is probably a portrait/actor image index, pointer-table index, or cached portrait slot.

Working interpretation:

```text
PP = portrait/actor index
```

Earlier idea that the high nibble was just a portrait modifier is probably too weak. It is more likely part of the same portrait/actor ID byte.

Scene behavior:

- Portrait/actor numbering appears scene-local.
- `01` is often Musashi, but this is not guaranteed globally.
- Other values may point to characters loaded for the current scene.
- Invalid/unloaded values produce garbage or blank portrait space.

Practical rule:

```text
Do not casually change PP.
Keep the original final byte unless deliberately testing portrait/actor behavior.
```

Examples:

```text
[020201]
```

Likely means:

```text
02 = textbox command
0  = normal inset
2  = normal speaking textbox
01 = portrait/actor index 01
```

```text
[026201]
```

Likely means:

```text
02 = textbox command
6  = unsafe/high inset, may freeze
2  = normal speaking textbox
01 = portrait/actor index 01
```

```text
[020601]
```

Likely means:

```text
02 = textbox command
0  = normal inset
6  = spiky/yelling textbox
01 = portrait/actor index 01
```

---

## Choices

| Tag pattern | Meaning |
|---|---|
| `[sel][0C]` | two choices |
| `[sel][0D]` | three choices |

Preserve the choice structure carefully. Choice text is editable, but the selection controls determine how many options are expected.

---

## Runtime variable insertion

| Tag | Working meaning |
|---|---|
| `[0E]` | runtime value/string/number from RAM |
| `[0F]` | alternate runtime value/string/number from RAM |

Examples:

```text
Musashi found [c2]Handle# [0E][c1].
Now there are [0E] hurt Minku in my care.
```

Do not replace `[0E]` or `[0F]` with literal text unless intentionally removing dynamic behavior.

---

## Practical editing rules

For normal text edits:

```text
Leave column D alone.
Edit only visible text in column F.
Use [c1]-[cF], [br], [wt], and [new] in column F when needed.
```

For longer cutscene lines:

```text
Increase [14xx00] duration.
```

Example:

```text
Original:
[19][143C00]Short line[15]

Longer edit:
[19][147800]Longer edited line that needs more time[15]
```

To force manual advance:

```text
[19][147800]Important line[16]
```

Use `[16]` carefully because it changes cutscene pacing.

For control-code experiments:

```text
Change column D if replacing existing controls.
Add controls in column F only if intentionally adding extra controls.
Put unchanged original text in column F if you want a column-D-only control change to be imported.
```

Avoid casually changing these until better understood:

```text
[05]
[0Bxxxxxx]
[10xxxx]
[11xxxx]
[18]
[19]
[02xxxx]
[03xx]
```

---

## Known working pipeline assumptions

- `CtrlEn.decode()` must output `[br]` for byte `0A`, not a normal space.
- `EncodingEn` must be used for English import.
- No-op round-trip tests should produce no byte differences for the edited sentence.
- If no-op changes produce byte differences, a control code or encoding rule is still wrong.
