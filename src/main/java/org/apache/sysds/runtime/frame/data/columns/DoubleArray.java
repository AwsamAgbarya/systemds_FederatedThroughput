/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.frame.data.columns;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import org.apache.sysds.common.Types.ValueType;


public class DoubleArray extends Array<Double> {
	private double[] _data = null;

	public DoubleArray(double[] data) {
		_data = data;
		_size = _data.length;
	}

	public double[] get() {
		return _data;
	}

	@Override
	public Double get(int index) {
		return _data[index];
	}

	@Override
	public void set(int index, Double value) {
		_data[index] = (value != null) ? value : 0d;
	}

	@Override
	public void set(int rl, int ru, Array<Double> value) {
		set(rl, ru, value, 0);
	}

	@Override
	public void set(int rl, int ru, Array<Double> value, int rlSrc) {
		System.arraycopy(((DoubleArray) value)._data, rlSrc, _data, rl, ru - rl + 1);
	}

	@Override
	public void setNz(int rl, int ru, Array<Double> value) {
		double[] data2 = ((DoubleArray) value)._data;
		for(int i = rl; i < ru + 1; i++)
			if(data2[i] != 0)
				_data[i] = data2[i];
	}

	@Override
	public void append(String value) {
		append((value != null) ? Double.parseDouble(value) : null);
	}

	@Override
	public void append(Double value) {
		if(_data.length <= _size)
			_data = Arrays.copyOf(_data, newSize());
		_data[_size++] = (value != null) ? value : 0d;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		for(int i = 0; i < _size; i++)
			out.writeDouble(_data[i]);
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		_size = _data.length;
		for(int i = 0; i < _size; i++)
			_data[i] = in.readDouble();
	}

	@Override
	public Array<Double> clone() {
		return new DoubleArray(Arrays.copyOf(_data, _size));
	}

	@Override
	public Array<Double> slice(int rl, int ru) {
		return new DoubleArray(Arrays.copyOfRange(_data, rl, ru + 1));
	}

	@Override
	public void reset(int size) {
		if(_data.length < size)
			_data = new double[size];
		_size = size;
	}

	@Override
	public byte[] getAsByteArray(int nRow) {
		ByteBuffer doubleBuffer = ByteBuffer.allocate(8 * nRow);
		doubleBuffer.order(ByteOrder.nativeOrder());
		for(int i = 0; i < nRow; i++)
			doubleBuffer.putDouble(_data[i]);
		return doubleBuffer.array();
	}

	@Override
	public ValueType getValueType() {
		return ValueType.FP64;
	}
}