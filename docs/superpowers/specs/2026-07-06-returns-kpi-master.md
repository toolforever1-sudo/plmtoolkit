# Returns Tracker revamp + KPI Master (Vikas Singh feedback, 2026-07-06)
Source: RE_ Returns Tracker eml. Test on local (points to PROD db), stage to QA.

## Returns Tracker (returnstracker.js + EcnReportService/Controller)
- A. Remove "33 ECNs excluded from AI analysis" callout + "Executive Narrative (AI-generated)" section
- B. Events table: default-sort by ECN#, columns sortable
- C. Fix "Change Type" blank in many rows
- D. Fix "No audit code" value in the audit-enabled Categories bar chart
- E. Top Product Lines hover tooltip: include % within each product line
- F. Move Repeat Requestors + Product Teams tiles ABOVE the Trend tile; add per-category stacked color bars (like Product Lines)
- G. Remove "AI -> Audit Mismatches" section

## KPI Master (new tab in ECN Dashboard)
- Centralize SLA Targets editor (currently ecnreport.js) + D029-00006 mapping:
  Request Classification + Subclass -> Target Std, Target Urgent, ECN Classification, Change Type
- All dashboards draw ECN Classification / Change Type / SLA targets from this lookup
- Format fixed per D029-00006 (image008): cols A Request Classification, B Subclass, C Target Std, D Target Urgent, E ECN Classification, F Change Type
- Existing SLA editor buttons: Save as Profile / Reset to Baseline / Promote to Active SLA
