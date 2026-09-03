-- Run this against the team Supabase project BEFORE installing a build that contains the
-- matching TelemetryUploader change. PostgREST rejects an insert naming a column the table does
-- not have (PGRST204) and rejects the whole batch, so an app that sends these columns against an
-- un-migrated table uploads nothing at all — visible in logcat as
-- "[TelemetryUploader] upload failed HTTP 400".
--
-- Safe to run more than once. Every column is nullable: rows uploaded by older builds keep
-- inserting fine, they just leave these NULL.
--
-- Apply via the Supabase dashboard SQL editor, or:
--   psql "$SUPABASE_DB_URL" -f supabase/add_filter_columns.sql

alter table public.telemetry
    -- Filter state. The two bias columns are the convergence checks for the online calibrators:
    -- each should climb to a stable device-specific value and stay there. One that keeps
    -- wandering is absorbing noise rather than tracking a bias.
    add column if not exists gyro_bias_dps    double precision,
    add column if not exists dv_bias          double precision,
    add column if not exists heading_unc_deg  double precision,

    -- Gate statistics. Both gates are measured before they are ever enforced: gnss_nis is
    -- chi-square with 2 degrees of freedom (a healthy filter sits mostly below ~6), and the
    -- distribution it shows here is what decides whether use_gnss_nis_gate can be switched on.
    add column if not exists gnss_nis         double precision,
    add column if not exists yaw_clamp_count  bigint,

    -- Map matching. use_map_match_fusion is ON in the shipped config.json (the Kotlin default is
    -- off, the asset overrides it), so map_unc_m is a live input to position, not a display-only
    -- readout -- which is exactly why the column is worth having.
    add column if not exists map_on_road      boolean,
    add column if not exists map_unc_m        double precision,

    -- Compass. Always recorded; fused only when use_mag_heading is on, which it is not by
    -- default. mag_heading_deg is the phone's azimuth from MAGNETIC north; the filter's heading is
    -- the vehicle's. The question a drive answers is whether (mag_heading_deg - heading) holds
    -- steady per mount while mag_accuracy = 3, which is what justifies turning that flag on.
    add column if not exists mag_heading_deg  double precision,
    add column if not exists mag_accuracy     integer,

    -- The filter's estimate of how the phone sits in its cradle, degrees. NULL unless
    -- use_mag_heading is on, so a non-null value here is always a real estimate. One that
    -- converges and then walks is the phone moving in the mount.
    add column if not exists mount_offset_deg double precision;
