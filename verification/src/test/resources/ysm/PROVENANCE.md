The binary legacy_v3_dynamic_vector.ysm is copied from the approved
AndreaFrederica/OhMyYesSteveModel reference, commit
d2536542747a1aa5c02594827f579171c525843c, path
runtime/java-v3/src/test/resources/legacy_v3_dynamic_vector.ysm.

SHA-256: 548e5610a57e561f84878882f14dd3557aac1c73c073accc5ee970312d8ac2f3
The upstream note says historical city_d.lib/chacha_d.lib generated this
12,095-byte vector, with a dynamic ChaCha chunk boundary and dirty zstd.
Expected plaintext: LE32 version 1, then 12,000 low bytes of consecutive
std::mt19937_64 outputs with seed 0xD15EA5E. Expected plaintext SHA-256
3e41fd30736a8b89eb043e8eeefc7cc328491580e88a395ab2c4062df5ad0ea6 was
calculated independently using a separate Python MT19937-64 generator.

This is a synthetic transform vector, not a semantically valid model.
No OpenYSM fixture or oracle is used.
