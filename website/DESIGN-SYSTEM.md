# Bag It: design system and decisions

Generated with the UI/UX Pro Max skill (local BM25 lookups over its CSV reference data; scripts read before running, no instructions in data files followed), then reconciled with the established Bag It brand. Brand decisions win where they conflict.

## 1. Skill output (verbatim)

## Design System: Bag It

### Pattern
- **Name:** Minimal Single Column
- **Conversion Focus:** Single CTA focus. Large typography. Lots of whitespace. No nav clutter. Mobile-first.
- **CTA Placement:** Center, large CTA button
- **Color Strategy:** Minimalist: Brand + white #FFFFFF + accent. Buttons: High contrast 7:1+. Text: Black/Dark grey
- **Sections:** 1. Hero headline, 2. Short description, 3. Benefit bullets (3 max), 4. CTA, 5. Footer

### Style
- **Name:** Liquid Glass
- **Keywords:** Flowing glass, morphing, smooth transitions, fluid effects, translucent, animated blur, iridescent, chromatic aberration
- **Best For:** Premium SaaS, high-end e-commerce, creative platforms, branding experiences, luxury portfolios
- **Performance:** ⚠ Moderate-Poor | **Accessibility:** ⚠ Text contrast

### Colors
| Role | Hex |
|------|-----|
| Primary | #1C1917 |
| Secondary | #44403C |
| CTA | #CA8A04 |
| Background | #FAFAF9 |
| Text | #0C0A09 |

*Notes: Black + Gold (#FFD700) + White + Minimal accent*

### Typography
- **Heading:** Rubik
- **Body:** Nunito Sans
- **Mood:** ecommerce, clean, shopping, product, retail, conversion
- **Best For:** E-commerce, online stores, product pages, retail, shopping
- **Google Fonts:** https://fonts.google.com/share?selection.family=Nunito+Sans:wght@300;400;500;600;700|Rubik:wght@300;400;500;600;700
- **CSS Import:**
```css
@import url('https://fonts.googleapis.com/css2?family=Nunito+Sans:wght@300;400;500;600;700&family=Rubik:wght@300;400;500;600;700&display=swap');
```

### Key Effects
Morphing elements (SVG/CSS), fluid animations (400-600ms curves), dynamic blur (backdrop-filter), color transitions

### Avoid (Anti-patterns)
- Cheap visuals
- Fast animations

### Pre-Delivery Checklist
- [ ] No emojis as icons (use SVG: Heroicons/Lucide)
- [ ] cursor-pointer on all clickable elements
- [ ] Hover states with smooth transitions (150-300ms)
- [ ] Light mode: text contrast 4.5:1 minimum
- [ ] Focus states visible for keyboard nav
- [ ] prefers-reduced-motion respected
- [ ] Responsive: 375px, 768px, 1024px, 1440px

## 2. Supplementary lookups used
- Landing: **Product Demo + Features** (hero → product video centre → feature breakdown → CTA; autoplay muted) and **Video-First Hero** (captions/text alternative for video, compress video).
- UX: reduced motion (High), excessive motion (1-2 animated elements per view), easing (ease-out in), touch targets ≥44×44 px (High), contrast ≥4.5:1 (High), visible focus states (High).
- Typography: Rubik + Nunito Sans (e-commerce), Inter (minimal swiss), Satoshi/General Sans (premium).
- Colour: luxury/premium retail = stone neutrals #1C1917 / #FAFAF9 with gold CTA #CA8A04.
- Stack (html-tailwind rows, applied as plain CSS): fixed aspect ratios on media, reduced-motion overrides, focus-visible only for keyboard.

## 3. Applied (priority order)
1. **Accessibility:** text ≥4.5:1 in light and dark; ink focus ring on every control (inverted ring on video controls); skip link; landmarks; each video has a name, a visible caption via aria-describedby, and its own Play/Pause button (WCAG 2.2.2); no audio, so no captions track needed.
2. **Touch:** every interactive target ≥44 px (header CTA, nav and footer links, FAQ rows, video buttons, text links).
3. **Performance:** no external requests, system fonts, `preload="none"` on video (hero switches to auto only when it will autoplay), portrait cuts (4:5 hero, 720×1160 demo) for screens under 600 px, posters chosen per width, autoplay off for reduced motion and Save-Data; hero ≈1.0 MB WebM, demo ≈0.24 MB.
4. **Layout:** Minimal Single Column + Product Demo pattern: hero (headline, one paragraph, one primary CTA, film), 3 beats (skill: benefit bullets max 3), demo, get the app, short FAQ, footer. Checked at 375, 768, 1024, 1440.
5. **Type/colour:** high-contrast ink buttons (≥7:1, as the pattern asks); neutral palette.
6. **Animation:** only the films move; hover/colour transitions 200 ms ease-out; reduced motion = posters + native controls.
7. **Style:** minimal, hairlines, no decorative effects.

## 4. Where Scott's taste overrode the skill
| Skill said | Decision | Why |
|---|---|---|
| Style "Liquid Glass" (morphing, animated blur, iridescence) | Rejected; flat neutral surfaces, header is solid (blur removed) | No glassmorphism/decorative effects; skill itself flags poor performance and contrast |
| Gold CTA #CA8A04, stone neutrals | Ink pill buttons, zinc neutrals; coral #E8654A only as the bag's light | One accent with one meaning; established brand |
| Rubik + Nunito Sans from Google Fonts | System font stack | No external font CDNs; no strong case for a self-hosted face |
| Morphing elements / 400-600 ms fluid effects | Only the product films move | Calm hardware feel; skill's own "animate 1-2 elements" rule |
| Icons via Heroicons/Lucide | No icon set; numbers carry the 3 beats | Fewer elements; no emoji icons either way |

## 5. Pre-delivery checklist
- [x] No emojis as icons
- [x] cursor:pointer on clickable elements
- [x] Hover states with 150-300 ms transitions (200 ms)
- [x] Light-mode text contrast ≥4.5:1 (dark mode too)
- [x] Visible focus states
- [x] prefers-reduced-motion respected
- [x] Responsive at 375 / 768 / 1024 / 1440
