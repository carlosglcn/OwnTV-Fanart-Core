RPU samples from quietvoid/dovi_tool (MIT): `*_orig.bin` are its test RPUs (4-byte start code + escaped
payload); `*_to_81.bin` are what libdovi's `convert_with_mode(2)` writes for them (NAL header `7C 01` +
payload). `DoviP7Test` checks the port gives the same bytes.
