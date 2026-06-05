# Brave Fencer Musashi English Control Code Notes - v4 Addendum

Add these notes to `CONTROL_CODES_EN.md`.

---

## `[1A]` through `[1F]`

Current test result:

```text
[1A]
[1B]
[1C]
[1D]
[1E]
[1F]
```

Observed behavior:

- No visible effect in the tested text/cutscene context.
- They do not appear to act like normal text engine controls.
- Treat them as unused, ignored, or context-specific until proven otherwise.

Working conclusion:

```text
[01]-[19] appear to be the main meaningful low-byte text control range.
[1A]-[1F] currently appear unused/no-op.
[20]-[FF] are more likely printable glyph bytes, special glyph bytes, or command parameters rather than top-level controls.
```

---

## Special symbols / special glyph codes

These are not normal control codes like `[04xx]` or `[02xxxx]`.

They appear to be special two-byte glyph codes from the font/charset mapping.

Tool-maker mapping:

| Bytes | Symbol meaning |
|---|---|
| `80 81` | `{moon}` |
| `82 83` | `{fire}` |
| `84 85` | `{water}` |
| `86 87` | `{wind}` |
| `88 89` | `{air}` |
| `8A 8B` | `{earth}` |
| `8C 8D` | `{sun}` |
| `90 91` | `¡ð` = `○` circle button |
| `92 93` | `¡õ` = `△` triangle button |
| `94 95` | `¡÷` = `□` square button |
| `96 97` | `¡Á` = `×` X button |

Preferred human-readable notes:

```text
□ = square button
× = X button
△ = triangle button
○ = circle button
```

Element symbols:

```text
{moon}
{fire}
{water}
{wind}
{air}
{earth}
{sun}
```

Important distinction:

```text
[04xx], [02xxxx], [15], etc. = control codes
{fire}, ○, △, □, ×, etc. = special glyphs/font symbols
```

The special glyphs use bytes above the main control range, so they are probably handled by the font/encoding layer rather than the control-code interpreter.

---

## Suggested EncodingEn aliases

If the English encoder does not already handle these, add aliases so column F can use readable symbols.

Button aliases:

```java
addAlias(reverse, "○", "¡ð");
addAlias(reverse, "△", "¡õ");
addAlias(reverse, "□", "¡÷");
addAlias(reverse, "×", "¡Á");
```

Element aliases, if the target names exist in the charset table:

```java
addAlias(reverse, "{moon}", "{moon}");
addAlias(reverse, "{fire}", "{fire}");
addAlias(reverse, "{water}", "{water}");
addAlias(reverse, "{wind}", "{wind}");
addAlias(reverse, "{air}", "{air}");
addAlias(reverse, "{earth}", "{earth}");
addAlias(reverse, "{sun}", "{sun}");
```

Better manual byte aliases, if needed:

```java
reverse.put("{moon}", new byte[] {(byte)0x80, (byte)0x81});
reverse.put("{fire}", new byte[] {(byte)0x82, (byte)0x83});
reverse.put("{water}", new byte[] {(byte)0x84, (byte)0x85});
reverse.put("{wind}", new byte[] {(byte)0x86, (byte)0x87});
reverse.put("{air}", new byte[] {(byte)0x88, (byte)0x89});
reverse.put("{earth}", new byte[] {(byte)0x8A, (byte)0x8B});
reverse.put("{sun}", new byte[] {(byte)0x8C, (byte)0x8D});

reverse.put("○", new byte[] {(byte)0x90, (byte)0x91});
reverse.put("△", new byte[] {(byte)0x92, (byte)0x93});
reverse.put("□", new byte[] {(byte)0x94, (byte)0x95});
reverse.put("×", new byte[] {(byte)0x96, (byte)0x97});
```

Note:

- If the sentence splitter breaks `{fire}` into separate characters, the serializer needs to recognize brace tokens as one unit before normal character splitting.
- The button symbols `○`, `△`, `□`, and `×` are single visible Unicode characters, so they are easier to support cleanly.
