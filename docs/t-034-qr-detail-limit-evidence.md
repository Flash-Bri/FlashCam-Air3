# T-034 — QR Detail-Limit Evidence Closure (Air3)

Date: 2026-03-18  
Scope: Evidence-only runtime analysis on Air3 (no feature changes)

## Goal
Determine whether normal-distance QR failures are primarily caused by software path choices or by Air3 device/optics limits.

## Runtime Evidence Collected

### 1) Camera/stream configuration (from diagnostic logs)
- Preview stream in use: **1920x1440**
- QR analysis stream in use: **1920x1440**
- Reported YUV sizes include one larger mode: **2048x1536**
- AF support: **none** (`afModes=[0]`)
- AF regions: **none** (`maxAFRegions=0`)
- AE regions: reported but AF unavailable
- Minimum focus distance: **0.0**

Interpretation: this path behaves as fixed-focus in practice, with no autofocus control available to improve distant QR sharpness.

### 2) Success-case decode footprint (CASE S)
From 7 decode-success log lines at 1920x1440:
- Average QR span: **~192 px (W) x 185 px (H)**
- Approx normalized size: **~10% frame width x ~13% frame height**
- Smallest observed success span: **185x177.5 px** (approx **9.6% x 12.3%**)

Interpretation: decode reliability appears to require a relatively large in-frame QR footprint.

### 3) Failure-case behavior (CASE F)
- Camera diagnostic line present
- **No decode-success lines** during visually clear but farther-distance QR test

Interpretation: confirms an observable threshold between successful and failed QR footprint/detail conditions.

## Conclusion

### A) Is a higher-detail analysis path still worth trying?
**Limited headroom only.**
Current analysis is already near maximum (1920x1440 vs 2048x1536 available). The larger mode would add only modest pixel detail and is unlikely to materially lower the required QR frame occupancy by itself.

### B) Are we near a practical Air3 hardware/optics ceiling for normal-distance small QR?
**Likely yes.**
Given:
- near-max analysis resolution already used,
- no autofocus control path available,
- and observed decode threshold requiring large in-frame QR footprint,

the dominant constraint appears to be device optics/sensor detail limits at distance rather than missing decode plumbing.

## Decision Record
- T-034 closes as **evidence complete**.
- QR range-extension work is **frozen** pending explicit new direction.
- Any follow-up should be treated separately as UX mitigation/product behavior, not as assumed range-fix via software alone.
