import json
import unittest
from unittest.mock import Mock
from http_snapshot import get_snapshot


class SnapshotTests(unittest.TestCase):
    def test_truncated_json_is_retried_as_a_get_without_repeating_a_mutation(self):
        client, sleep = Mock(), Mock()
        client.request.side_effect = [json.JSONDecodeError('Unterminated string', '{"value":"', 9), {'value': 'complete'}]
        self.assertEqual(get_snapshot(client, '/api/dashboard?symbol=XAU_USDT', sleep=sleep), {'value': 'complete'})
        self.assertEqual(client.request.call_count, 2)
        for call in client.request.call_args_list:
            self.assertEqual(call.args, ('/api/dashboard?symbol=XAU_USDT',))
            self.assertEqual(call.kwargs, {})
        sleep.assert_called_once_with(1)

    def test_exhaustion_remains_a_failure(self):
        client, sleep = Mock(), Mock()
        client.request.side_effect = json.JSONDecodeError('truncated', '{', 1)
        with self.assertRaises(json.JSONDecodeError):
            get_snapshot(client, '/api/dashboard', sleep=sleep)
        self.assertEqual(client.request.call_count, 3)
        self.assertEqual(sleep.call_count, 2)

    def test_success_is_not_repeated_and_unexpected_failure_is_not_hidden(self):
        client, sleep = Mock(), Mock()
        client.request.return_value = {'ok': True}
        self.assertEqual(get_snapshot(client, '/api/session', sleep=sleep), {'ok': True})
        self.assertEqual(client.request.call_count, 1)
        sleep.assert_not_called()
        client.request.side_effect = ValueError('invalid data')
        with self.assertRaisesRegex(ValueError, 'invalid data'):
            get_snapshot(client, '/api/session', sleep=sleep)
