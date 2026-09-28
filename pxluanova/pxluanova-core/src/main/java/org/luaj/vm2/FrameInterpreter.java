package org.luaj.vm2;

import java.util.Deque;

import org.luaj.vm2.lib.LuaContinuableFunction;

class FrameInterpreter {

	static Varargs run(LuaThread.State s) {
		Deque<LuaFrame> frames = s.frameStack;

		try {
			while (!frames.isEmpty()) {
				if (s.yieldRequested) {
					if (s.yieldIsInterrupt) {
						s.yieldRequested = false;
						s.yieldIsInterrupt = false;
					} else {
						s.yieldRequested = false;
						LuaFrame frame = frames.peek();
						if (frame.storedFunc != null) {
							LuaValue func = frame.storedFunc;
							Varargs origArgs = frame.storedCallArgs;
							Object cont = frame.storedContinuation;
							frame.storedFunc = null;
							frame.storedCallArgs = null;
							frame.storedContinuation = null;
							@SuppressWarnings("unchecked")
							LuaContinuableFunction<Object> lcf = (LuaContinuableFunction<Object>) func;
							Varargs ret;
							try {
								ret = lcf.invoke(origArgs, cont);
							} catch (YieldContinuationException yce) {
								frame.storedFunc = yce.func;
								frame.storedCallArgs = yce.callArgs;
								frame.storedContinuation = yce.continuation;
								s.yieldRequested = true;
								s.status = LuaThread.STATUS_SUSPENDED;
								s.result = yce.continuation instanceof Varargs v ? v : LuaValue.NONE;
								return s.result;
							}
							int ci = frame.closure.p.code[frame.pc];
							int a = (ci >> 6) & 0xff;
							int c = (ci >> 14) & 0x1ff;
							if (c > 0) {
								ret.copyto(frame.stack, a, c - 1);
								frame.v = LuaValue.NONE;
							} else {
								frame.top = a + ret.narg();
								frame.v = ret.dealias();
							}
							frame.pc++;
							s.resumeArgs = LuaValue.NONE;
						} else {
							int ci = frame.closure.p.code[frame.pc];
							int ca = (ci >> 6) & 0xff;
							int cc = (ci >> 14) & 0x1ff;
							Varargs ra = s.resumeArgs;
							if (cc > 0) {
								// FIXME: idk it should be cc - 1 or just cc
								ra.copyto(frame.stack, ca, cc);
								frame.v = LuaValue.NONE;
							} else {
								frame.top = ca + ra.narg();
								frame.v = ra.dealias();
							}
							frame.pc++;
							s.resumeArgs = LuaValue.NONE;
						}
					}
				}

				if (!step(s, frames))
					return s.result != null ? s.result : LuaValue.NONE;
			}
			return s.result != null ? s.result : LuaValue.NONE;
		} catch (LuaError le) {
			if (le.traceback == null)
				unwindFrames(frames, le);
			throw le;
		} catch (Exception e) {
			LuaError le = new LuaError(e);
			unwindFrames(frames, le);
			throw le;
		}
	}

	static void unwindFrames(Deque<LuaFrame> frames, LuaError le) {
		while (!frames.isEmpty()) {
			LuaFrame frame = frames.pop();
			try {
				le.fileline = (frame.closure.p.source != null ? frame.closure.p.source.tojstring() : "?")
					+ ":"
					+ (frame.closure.p.lineinfo != null && frame.pc >= 0
						&& frame.pc < frame.closure.p.lineinfo.length
							? String.valueOf(frame.closure.p.lineinfo[frame.pc])
							: "?")
					+ ": ";
				frame.closure.errorHook(le, le.level);
			} catch (Throwable ignored) {
			}
			closeOpenUps(frame);
			if (frame.closure.state != null && frame.closure.state.debuglib != null)
				frame.closure.state.debuglib.onReturn();
		}
	}

	static boolean step(LuaThread.State s, Deque<LuaFrame> frames) throws LuaError {
		LuaFrame frame = frames.peek();
		LuaState state = s.state;

		LuaClosure closure = frame.closure;
		Prototype p = closure.p;
		int[] code = p.code;
		LuaValue[] k = p.k;
		LuaValue[] stack = frame.stack;
		UpValue[] upValues = closure.upValues;
		UpValue[] openups = frame.openups;

		if (state != null && state.debuglib != null)
			state.debuglib.onInstruction(frame.pc, frame.v, frame.top);
		if (state != null && --state.checkpointCountdown <= 0)
			state.checkpoint();

		if (s.yieldRequested)
			return false;

		int i = code[frame.pc];
		frame.pc++;
		int a = (i >> 6) & 0xff;
		int b, c;
		LuaValue o;

		switch (i & 0x3f) {

		case Lua.OP_MOVE:
			stack[a] = stack[i >>> 23];
			return true;

		case Lua.OP_LOADK:
			stack[a] = k[i >>> 14];
			return true;

		case Lua.OP_LOADKX:
			i = code[frame.pc];
			frame.pc++;
			if ((i & 0x3f) != Lua.OP_EXTRAARG)
				throw new LuaError("OP_EXTRAARG expected after OP_LOADKX");
			stack[a] = k[i >>> 6];
			return true;

		case Lua.OP_LOADBOOL:
			stack[a] = (i >>> 23 != 0) ? LuaValue.TRUE : LuaValue.FALSE;
			if ((i & (0x1ff << 14)) != 0)
				frame.pc++;
			return true;

		case Lua.OP_LOADNIL:
			for (b = i >>> 23; b-- >= 0; )
				stack[a++] = LuaValue.NIL;
			return true;

		case Lua.OP_GETUPVAL:
			stack[a] = upValues[i >>> 23].getValue();
			return true;

		case Lua.OP_GETTABUP:
			c = (i >> 14) & 0x1ff;
			stack[a] = upValues[i >>> 23].getValue().get(
				c > 0xff ? k[c & 0x0ff] : stack[c]);
			return true;

		case Lua.OP_GETTABLE:
			c = (i >> 14) & 0x1ff;
			stack[a] = stack[i >>> 23].get(
				c > 0xff ? k[c & 0x0ff] : stack[c]);
			return true;

		case Lua.OP_SETTABUP:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			upValues[a].getValue().set(
				b > 0xff ? k[b & 0x0ff] : stack[b],
				c > 0xff ? k[c & 0x0ff] : stack[c]);
			return true;

		case Lua.OP_SETUPVAL:
			upValues[i >>> 23].setValue(stack[a]);
			return true;

		case Lua.OP_SETTABLE:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			stack[a].set(
				b > 0xff ? k[b & 0x0ff] : stack[b],
				c > 0xff ? k[c & 0x0ff] : stack[c]);
			return true;

		case Lua.OP_NEWTABLE:
			stack[a] = new LuaTable(i >>> 23, (i >> 14) & 0x1ff);
			return true;

		case Lua.OP_SELF:
			stack[a + 1] = (o = stack[i >>> 23]);
			c = (i >> 14) & 0x1ff;
			stack[a] = o.get(c > 0xff ? k[c & 0x0ff] : stack[c]);
			return true;

		case Lua.OP_ADD:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			stack[a] = OperationHelper.add(
				b > 0xff ? k[b & 0x0ff] : stack[b],
				c > 0xff ? k[c & 0x0ff] : stack[c]);
			return true;

		case Lua.OP_SUB:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			stack[a] = OperationHelper.sub(
				b > 0xff ? k[b & 0x0ff] : stack[b],
				c > 0xff ? k[c & 0x0ff] : stack[c]);
			return true;

		case Lua.OP_MUL:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			stack[a] = OperationHelper.mul(
				b > 0xff ? k[b & 0x0ff] : stack[b],
				c > 0xff ? k[c & 0x0ff] : stack[c]);
			return true;

		case Lua.OP_DIV:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			stack[a] = OperationHelper.div(
				b > 0xff ? k[b & 0x0ff] : stack[b],
				c > 0xff ? k[c & 0x0ff] : stack[c]);
			return true;

		case Lua.OP_MOD:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			stack[a] = OperationHelper.mod(
				b > 0xff ? k[b & 0x0ff] : stack[b],
				c > 0xff ? k[c & 0x0ff] : stack[c]);
			return true;

		case Lua.OP_POW:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			stack[a] = OperationHelper.pow(
				b > 0xff ? k[b & 0x0ff] : stack[b],
				c > 0xff ? k[c & 0x0ff] : stack[c]);
			return true;

		case Lua.OP_UNM:
			stack[a] = OperationHelper.neg(stack[i >>> 23]);
			return true;

		case Lua.OP_NOT:
			stack[a] = stack[i >>> 23].not();
			return true;

		case Lua.OP_LEN:
			stack[a] = OperationHelper.length(stack[i >>> 23]);
			return true;

		case Lua.OP_CONCAT:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			if (c > b + 1) {
				Buffer sb = stack[c].buffer();
				while (--c >= b)
					sb = sb.concatTo(stack[c]);
				stack[a] = sb.value();
			} else {
				stack[a] = OperationHelper.concat(stack[c - 1], stack[c]);
			}
			return true;

		case Lua.OP_JMP:
			frame.pc += (i >>> 14) - 0x1ffff;
			if (a > 0) {
				for (--a, b = openups.length; --b >= 0; )
					if (openups[b] != null && openups[b].index >= a) {
						openups[b].close();
						openups[b] = null;
					}
			}
			return true;

		case Lua.OP_EQ:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			if (OperationHelper.eq(
                    b > 0xff ? k[b & 0x0ff] : stack[b],
                    c > 0xff ? k[c & 0x0ff] : stack[c]) == (a == 0))
				frame.pc++;
			return true;

		case Lua.OP_LT:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			if (OperationHelper.lt(
                    b > 0xff ? k[b & 0x0ff] : stack[b],
                    c > 0xff ? k[c & 0x0ff] : stack[c]) == (a == 0))
				frame.pc++;
			return true;

		case Lua.OP_LE:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			if (OperationHelper.le(
                    b > 0xff ? k[b & 0x0ff] : stack[b],
                    c > 0xff ? k[c & 0x0ff] : stack[c]) == (a == 0))
				frame.pc++;
			return true;

		case Lua.OP_TEST:
			if (stack[a].toboolean() == ((i & (0x1ff << 14)) == 0))
				frame.pc++;
			return true;

		case Lua.OP_TESTSET:
			if ((o = stack[i >>> 23]).toboolean() == ((i & (0x1ff << 14)) == 0))
				frame.pc++;
			else
				stack[a] = o;
			return true;

		case Lua.OP_CALL:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			Varargs callArgs;
			if (b > 0)
				callArgs = LuaValue.varargsOf(stack, a + 1, b - 1);
			else
				callArgs = LuaValue.varargsOf(stack, a + 1,
					frame.top - frame.v.narg() - (a + 1), frame.v);
			{
				LuaValue func = stack[a];
				if (func == s.yieldSentinel) {
					s.result = callArgs;
					s.status = LuaThread.STATUS_SUSPENDED;
					s.yieldRequested = true;
					frame.pc--;
					return false;
				}
				if (func instanceof LuaClosure lc) {
					LuaValue[] newStack = new LuaValue[lc.p.maxstacksize];
					System.arraycopy(LuaValue.NILS, 0, newStack, 0, lc.p.maxstacksize);
					int nargs = callArgs.narg();
					int nfix = Math.min(nargs, lc.p.numparams);
					for (int j = 0; j < nfix; j++)
						newStack[j] = callArgs.arg(j + 1);
					Varargs newVarargs = lc.p.is_vararg != 0
						? callArgs.subargs(lc.p.numparams + 1)
						: LuaValue.NONE;
					LuaFrame newFrame = new LuaFrame();
					newFrame.closure = lc;
					newFrame.stack = newStack;
					newFrame.varargs = newVarargs;
					newFrame.v = LuaValue.NONE;
					newFrame.openups = lc.p.p.length > 0 ? new UpValue[newStack.length] : null;
					newFrame.callerOp = Lua.OP_CALL;
					newFrame.callerA = a;
					newFrame.callerB = b;
					newFrame.callerC = c;
					if (state != null && state.debuglib != null)
						state.debuglib.onCall(lc, newVarargs, newStack);
					frames.push(newFrame);
					return true;
				}
				if (state != null) state.enteringJavaCall();
				try {
					Varargs ret;
					try {
						ret = func.invoke(callArgs);
} catch (YieldContinuationException yce) {
					frame.storedFunc = yce.func;
					frame.storedCallArgs = yce.callArgs;
					frame.storedContinuation = yce.continuation;
					s.yieldRequested = true;
					s.status = LuaThread.STATUS_SUSPENDED;
					s.result = yce.continuation instanceof Varargs v ? v : LuaValue.NONE;
					frame.pc--;
					return false;
				}
					if (s.yieldRequested && !s.yieldIsInterrupt) {
						frame.pc--;
						return false;
					}
					if (c > 0) {
						ret.copyto(stack, a, c - 1);
						frame.v = LuaValue.NONE;
					} else {
						frame.top = a + ret.narg();
						frame.v = ret.dealias();
					}
				} finally {
					if (state != null) state.leavingJavaCall();
				}
			}
			return true;

		case Lua.OP_TAILCALL:
			b = i >>> 23;
			c = (i >> 14) & 0x1ff;
			Varargs tcArgs;
			if (b > 0)
				tcArgs = LuaValue.varargsOf(stack, a + 1, b - 1);
			else
				tcArgs = LuaValue.varargsOf(stack, a + 1,
					frame.top - frame.v.narg() - (a + 1), frame.v);
			{
				LuaValue tfunc = stack[a];
				if (tfunc == s.yieldSentinel) {
					s.result = tcArgs;
					s.status = LuaThread.STATUS_SUSPENDED;
					s.yieldRequested = true;
					frame.pc--;
					return false;
				}
				if (tfunc instanceof LuaClosure lc) {
					LuaValue[] newStack = new LuaValue[lc.p.maxstacksize];
					System.arraycopy(LuaValue.NILS, 0, newStack, 0, lc.p.maxstacksize);
					int nargs = tcArgs.narg();
					int nfix = Math.min(nargs, lc.p.numparams);
					for (int j = 0; j < nfix; j++)
						newStack[j] = tcArgs.arg(j + 1);
					Varargs newVarargs = lc.p.is_vararg != 0
						? tcArgs.subargs(lc.p.numparams + 1)
						: LuaValue.NONE;
					closeOpenUps(frame);
					if (state != null && state.debuglib != null)
						state.debuglib.onReturn();
					frame.closure = lc;
					frame.stack = newStack;
					frame.varargs = newVarargs;
					frame.v = LuaValue.NONE;
					frame.pc = 0;
					frame.top = 0;
					frame.openups = lc.p.p.length > 0 ? new UpValue[newStack.length] : null;
					if (state != null && state.debuglib != null)
						state.debuglib.onCall(lc, newVarargs, newStack);
					return true;
				}
				Varargs tcResult;
				try {
					tcResult = tfunc.invoke(tcArgs);
				} catch (YieldContinuationException yce) {
					frame.storedFunc = yce.func;
					frame.storedCallArgs = yce.callArgs;
					frame.storedContinuation = yce.continuation;
					s.yieldRequested = true;
					s.status = LuaThread.STATUS_SUSPENDED;
					s.result = yce.continuation instanceof Varargs v ? v : LuaValue.NONE;
					frame.pc--;
					return false;
				}
				if (s.yieldRequested && !s.yieldIsInterrupt) {
					frame.pc--;
					return false;
				}
				if (tcResult.isTailcall()) {
					TailcallVarargs tv = (TailcallVarargs) tcResult;
					tcResult = tv.eval();
				}
				closeOpenUps(frame);
				if (state != null && state.debuglib != null)
					state.debuglib.onReturn();
				int tcRetOp = frame.callerOp;
				int tcRetA = frame.callerA;
				int tcRetC = frame.callerC;
				frames.pop();
				if (frames.isEmpty()) {
					s.result = tcResult;
					return false;
				}
				LuaFrame tcCaller = frames.peek();
				if (tcRetOp == Lua.OP_CALL) {
					if (tcRetC > 0) {
						tcResult.copyto(tcCaller.stack, tcRetA, tcRetC - 1);
						tcCaller.v = LuaValue.NONE;
					} else {
						tcCaller.top = tcRetA + tcResult.narg();
						tcCaller.v = tcResult.dealias();
					}
				} else if (tcRetOp == Lua.OP_TFORCALL) {
					for (int j = 0; j < tcRetC; j++)
						tcCaller.stack[tcRetA + 3 + j] = tcResult.arg(j + 1);
					tcCaller.v = LuaValue.NONE;
				}
			}
			return true;

		case Lua.OP_RETURN:
			b = i >>> 23;
			Varargs result;
			switch (b) {
				case 0:
					result = LuaValue.varargsOf(stack, a,
						frame.top - frame.v.narg() - a, frame.v);
					break;
				case 1:
					result = LuaValue.NONE;
					break;
				case 2:
					result = stack[a];
					break;
				default:
					result = LuaValue.varargsOf(stack, a, b - 1);
					break;
			}
			closeOpenUps(frame);
			if (state != null && state.debuglib != null)
				state.debuglib.onReturn();
			int retOp = frame.callerOp;
			int retA = frame.callerA;
			int retC = frame.callerC;
			frames.pop();
			if (frames.isEmpty()) {
				s.result = result;
				return false;
			}
			LuaFrame caller = frames.peek();
			if (retOp == Lua.OP_CALL) {
				if (retC > 0) {
					result.copyto(caller.stack, retA, retC - 1);
					caller.v = LuaValue.NONE;
				} else {
					caller.top = retA + result.narg();
					caller.v = result.dealias();
				}
			} else if (retOp == Lua.OP_TFORCALL) {
				for (int j = 0; j < retC; j++)
					caller.stack[retA + 3 + j] = result.arg(j + 1);
				caller.v = LuaValue.NONE;
			}
			return true;

		case Lua.OP_FORLOOP:
			{
				LuaValue limit = stack[a + 1];
				LuaValue step  = stack[a + 2];
				LuaValue idx   = stack[a].add(step);
				if (step.gt_b(0) ? idx.lteq_b(limit) : idx.gteq_b(limit)) {
					stack[a] = idx;
					stack[a + 3] = idx;
					frame.pc += (i >>> 14) - 0x1ffff;
				}
			}
			return true;

		case Lua.OP_FORPREP:
			{
				LuaValue init  = stack[a].checknumber("'for' initial value must be a number");
				LuaValue limit = stack[a + 1].checknumber("'for' limit must be a number");
				LuaValue step  = stack[a + 2].checknumber("'for' step must be a number");
				stack[a] = init.sub(step);
				stack[a + 1] = limit;
				stack[a + 2] = step;
				frame.pc += (i >>> 14) - 0x1ffff;
			}
			return true;

		case Lua.OP_TFORCALL:
			{
				LuaValue iterFunc = stack[a];
				Varargs iterArgs = LuaValue.varargsOf(stack[a + 1], stack[a + 2]);
				c = (i >> 14) & 0x1ff;
				if (iterFunc instanceof LuaClosure lc) {
					LuaValue[] newStack = new LuaValue[lc.p.maxstacksize];
					System.arraycopy(LuaValue.NILS, 0, newStack, 0, lc.p.maxstacksize);
					int nargs = iterArgs.narg();
					int nfix = Math.min(nargs, lc.p.numparams);
					for (int j = 0; j < nfix; j++)
						newStack[j] = iterArgs.arg(j + 1);
					Varargs newVarargs = lc.p.is_vararg != 0
						? iterArgs.subargs(lc.p.numparams + 1)
						: LuaValue.NONE;
					LuaFrame newFrame = new LuaFrame();
					newFrame.closure = lc;
					newFrame.stack = newStack;
					newFrame.varargs = newVarargs;
					newFrame.v = LuaValue.NONE;
					newFrame.openups = lc.p.p.length > 0 ? new UpValue[newStack.length] : null;
					newFrame.callerOp = Lua.OP_TFORCALL;
					newFrame.callerA = a;
					newFrame.callerB = 0;
					newFrame.callerC = c;
					if (state != null && state.debuglib != null)
						state.debuglib.onCall(lc, newVarargs, newStack);
					frames.push(newFrame);
					return true;
				}
				frame.v = iterFunc.invoke(iterArgs);
				while (--c >= 0)
					stack[a + 3 + c] = frame.v.arg(c + 1);
				frame.v = LuaValue.NONE;
			}
			return true;

		case Lua.OP_TFORLOOP:
			if (!stack[a + 1].isnil()) {
				stack[a] = stack[a + 1];
				frame.pc += (i >>> 14) - 0x1ffff;
			}
			return true;

		case Lua.OP_SETLIST:
			c = (i >> 14) & 0x1ff;
			if (c == 0)
				c = code[frame.pc++];
			{
				int offset = (c - 1) * Lua.LFIELDS_PER_FLUSH;
				o = stack[a];
				if ((b = i >>> 23) == 0) {
					b = frame.top - a - 1;
					int m = b - frame.v.narg();
					int j = 1;
					for (; j <= m; j++)
						o.set(offset + j, stack[a + j]);
					for (; j <= b; j++)
						o.set(offset + j, frame.v.arg(j - m));
				} else {
					o.presize(offset + b);
					for (int j = 1; j <= b; j++)
						o.set(offset + j, stack[a + j]);
				}
			}
			return true;

		case Lua.OP_CLOSURE:
			{
				Prototype newp = p.p[i >>> 14];
				LuaClosure ncl = new LuaClosure(newp,
					state != null ? state.globals : null, state);
				Upvaldesc[] uv = newp.upvalues;
				for (int j = 0, nup = uv.length; j < nup; ++j) {
					if (uv[j].instack)
						ncl.upValues[j] = LuaClosure.findupval(stack, uv[j].idx, openups);
					else
						ncl.upValues[j] = upValues[uv[j].idx];
				}
				stack[a] = ncl;
			}
			return true;

		case Lua.OP_VARARG:
			b = i >>> 23;
			if (b == 0) {
				frame.top = a + (b = frame.varargs.narg());
				frame.v = frame.varargs;
			} else {
				for (int j = 1; j < b; ++j)
					stack[a + j - 1] = frame.varargs.arg(j);
			}
			return true;

		case Lua.OP_EXTRAARG:
			throw new IllegalArgumentException(
				"Unexecutable opcode: OP_EXTRAARG");

		default:
			throw new IllegalArgumentException(
				"Illegal opcode: " + (i & 0x3f));
		}
	}

	static void closeOpenUps(LuaFrame frame) {
		UpValue[] openups = frame.openups;
		if (openups != null)
			for (int u = openups.length; --u >= 0; )
				if (openups[u] != null)
					openups[u].close();
	}
}
