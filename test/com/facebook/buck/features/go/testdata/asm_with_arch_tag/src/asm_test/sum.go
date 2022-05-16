//go:build !386
// +build !386

package main

func Sum(xs []int64) int64 {
	var n int64
	for _, v := range xs {
		n += v
	}
	return n
}
