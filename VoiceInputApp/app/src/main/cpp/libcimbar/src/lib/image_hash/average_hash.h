/* This code is subject to the terms of the Mozilla Public License, v.2.0. http://mozilla.org/MPL/2.0/. */
#pragma once

#include "ahash_result.h"
#include "bit_extractor.h"
#include "bit_file/bitmatrix.h"
#include "cimb_translator/Cell.h"

#include "intx/intx.hpp"
#include <opencv2/opencv.hpp>

#include <array>
#include <bitset>
#include <cstdint>

namespace image_hash
{
	inline uint64_t average_hash(const cv::Mat& img, uchar threshold=0)
	{
		cv::Mat gray = img;
		if (img.channels() != 1)
			cv::cvtColor(gray, gray, cv::COLOR_RGB2GRAY);
		if (gray.cols > 8 or gray.rows > 8)
			cv::resize(gray, gray, cv::Size(8, 8));

		if (threshold == 0)
			threshold = Cell(gray).mean_grayscale();

		uint64_t res = 0;
		int bitpos = gray.rows*gray.cols - 1; // ex: 8*8 - 1
		for (int i = 0; i < gray.rows; ++i)
		{
			const uchar* p = gray.ptr<uchar>(i);
			for (int j = 0; j < gray.cols; ++j, --bitpos)
				res |= (uint64_t)(p[j] > threshold) << bitpos;
		}
		return res;
	}

	template <unsigned CELLSIZE>
	inline ahash_result<CELLSIZE> fuzzy_ahash(const cv::Mat& img, uchar threshold=0, unsigned mode=ahash_result<CELLSIZE>::ALL)
	{
		// return 9 uint64_ts, each representing a 5x5 section of the 7x7 img, an 8x8 section of an 10x10 img, etc
		cv::Mat gray = img;
		if (img.channels() != 1)
			cv::cvtColor(gray, gray, cv::COLOR_RGB2GRAY);

		if (threshold == 0)
			threshold = Cell(gray).mean_grayscale();

		intx::uint128 res(0);
		int bitpos = gray.cols*gray.rows - 1; // 8*8 - 1
		for (int i = 0; i < gray.rows; ++i)
		{
			const uchar* p = gray.ptr<uchar>(i);
			for (int j = 0; j < gray.cols; ++j, --bitpos)
				res |= intx::uint128(p[j] > threshold) << bitpos;
		}
		return ahash_result<CELLSIZE>(res, mode);
	}

	template <unsigned CELLSIZE>
	inline ahash_result<CELLSIZE> fuzzy_ahash(const bitmatrix& img, unsigned mode=ahash_result<CELLSIZE>::ALL)
	{
		const unsigned readlen = CELLSIZE+2;
		const unsigned mask = (1 << CELLSIZE) - 1;
		std::array<unsigned, CELLSIZE+2> rows;
		for (unsigned i = 0; i < readlen; ++i)
			rows[i] = img.get(0, i, readlen);

		std::array<uint64_t, 9> results = {0};
		unsigned resultCount = mode == ahash_result<CELLSIZE>::ALL ? 9 : 5;
		for (unsigned n = 0; n < resultCount; ++n)
		{
			unsigned id = ahash_result<CELLSIZE>::_ORDER[n];
			unsigned xoff = id % 3;
			unsigned yoff = id / 3;
			unsigned shift = readlen - xoff - CELLSIZE;
			uint64_t h = 0;
			for (unsigned y = 0; y < CELLSIZE; ++y)
				h = (h << CELLSIZE) | ((rows[yoff + y] >> shift) & mask);
			results[id] = h;
		}
		return ahash_result<CELLSIZE>(results, mode);
	}
}
