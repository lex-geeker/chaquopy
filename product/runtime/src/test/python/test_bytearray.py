from __future__ import print_function
from __future__ import division
from __future__ import absolute_import
import unittest
from chaquopy import autoclass

class StringArgumentForByteArrayTest(unittest.TestCase):

    def test_fill_byte_array(self):
        arr = [0, 0, 0]
        Test = autoclass('com.chaquo.python.BasicsTest')()
        Test.fillByteArray(arr)
        # we don't received signed byte, but unsigned in python (FIXME think about this)
        self.assertEquals(
            arr,
            [127, 1, 129])

    def test_create_bytearray(self):
        StringBufferInputStream = autoclass('java.io.StringBufferInputStream')
        nis = StringBufferInputStream("Hello world")
        barr = bytearray("\x00" * 5, encoding="utf8")
        self.assertEquals(nis.read(barr, 0, 5), 5)
        self.assertEquals(barr, b"Hello")

    def test_bytearray_ascii(self):
        ByteArrayInputStream = autoclass('java.io.ByteArrayInputStream')
        s = b"".join(bytes(x) for x in range(256))
        nis = ByteArrayInputStream(bytearray(s))
        barr = bytearray("\x00" * 256, encoding="ascii")
        self.assertEquals(nis.read(barr, 0, 256), 256)
        self.assertEquals(barr[:256], s[:256])
