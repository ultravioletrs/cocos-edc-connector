# Attestation Token Validation

This runbook describes how to recover a host whose signed Cocos/KBS token reports
an old TCB or `ear.status: contraindicated`. It does not modify, synthesize, or
forge attestation evidence.

## 1. Verify the host and services

Confirm that BIOS, microcode, TDX, QGS, PCCS, KBS, the connector, and the CVM
are running. The connector endpoint used in this deployment is:

```text
POST /api/management/cocos/computations/{jobId}/attestation-token
```

Decode only the signed token payload and record:

```text
ear.status
tdx.tcb_status
tdx.tcb_date
tdx.advisory_ids
```

Never replace these values locally. They must come from the signed token.

## 2. Refresh Intel platform collateral

After a BIOS or microcode update, clear the QGS local cache only after making a
backup, then restart QGS. If PCCS has no current PCK certificate for the new
CPU SVN, register the platform using Intel's installed tool:

```bash
PCKIDRetrievalTool \
  -f /tmp/pckid-retrieval.csv \
  -url https://localhost:8081 \
  -user_token "$PCCS_USER_TOKEN" \
  -use_secure_cert false \
  -tcb_update_type all
```

The PCCS user token must be supplied through the deployment secret mechanism.
Do not commit it or print it in logs. The resulting PCK certificate must be
issued by Intel PCCS/PCS; clearing the cache alone is not sufficient.

## 3. Configure KBS reference values

The default CPU policy requires reference values for the known CVM image and
runtime measurements (`mr_td`, `rtmr_1`, `rtmr_2`, and `xfam`). Missing values
are reported in the KBS log as:

```text
No reference value found for the given id
```

Register values only through authenticated KBS admin access. For a temporary
maintenance operation:

1. Back up `kbs-config.toml`.
2. Enable `AuthenticatedAuthorization` with a short-lived local admin key.
3. Register a signed or otherwise approved provenance message through
   `/kbs/v0/reference-value`.
4. Request a fresh connector token and verify `ear.status` and `tcb_status`.
5. Use an exit trap or equivalent guaranteed cleanup to restore `DenyAll`,
   delete the temporary key and token, and restart KBS.

Do not use `InsecureAllowAll` in a shared or reachable deployment. Do not add
measurements merely because they make a token pass; they must correspond to the
approved CVM image and be reviewed as policy data.

## 4. Final verification

Request a new token after cleanup. The expected result for a fully validated
platform is:

```text
ear.status: affirming
tdx.tcb_status: UpToDate
```

Also verify that KBS is back to `authorization_mode = "DenyAll"`, temporary
admin files are absent, and the connector, KBS, PCCS, QGS, and CVM services are
active.
