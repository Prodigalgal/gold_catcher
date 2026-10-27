import unittest
from gold_catcher_mcp import header_name, validate_endpoint


class BridgeBoundaryTest(unittest.TestCase):
    def test_only_https_or_explicit_loopback_and_no_credential_query_or_fragment(self):
        self.assertEqual(validate_endpoint('https://gold.mnnu.eu.org/api/mcp'), 'https://gold.mnnu.eu.org/api/mcp')
        self.assertEqual(validate_endpoint('http://127.0.0.1:18084/api/mcp'), 'http://127.0.0.1:18084/api/mcp')
        for value in ('http://outside.example/api/mcp', 'https://user:pass@gold.mnnu.eu.org/api/mcp', 'https://gold.mnnu.eu.org/api/mcp?token=secret', 'https://gold.mnnu.eu.org/api/mcp#token'):
            with self.assertRaises(ValueError):
                validate_endpoint(value)

    def test_header_name_encoding_preserves_sentinel_and_non_ascii_values(self):
        self.assertEqual(header_name('get_quote'), 'get_quote')
        self.assertEqual(header_name('中文'), '=?base64?5Lit5paH?=')
        self.assertTrue(header_name('=?base64?literal?=').startswith('=?base64?PT9'))
