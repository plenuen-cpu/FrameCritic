import pathlib,urllib.request
root=pathlib.Path(__file__).resolve().parents[1]
folder=root/'app/src/main/assets/licenses';folder.mkdir(parents=True,exist_ok=True)
files={
 'NIMA-Apache-2.0.txt':'https://raw.githubusercontent.com/idealo/image-quality-assessment/master/LICENSE',
 'llama-MIT.txt':'https://raw.githubusercontent.com/ggml-org/llama.cpp/956591f4570aa6636895d90439892fef7c075b17/LICENSE',
 'Qwen3-Apache-2.0.txt':'https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/23749fefcc72300e3a2ad315e1317431b06b590a/LICENSE',
 'MediaPipe-Apache-2.0.txt':'https://raw.githubusercontent.com/google-ai-edge/mediapipe/v0.10.21/LICENSE',
 'OpenCV-Apache-2.0.txt':'https://raw.githubusercontent.com/opencv/opencv/4.12.0/LICENSE',
 'TensorFlow-Apache-2.0.txt':'https://raw.githubusercontent.com/tensorflow/tensorflow/v2.17.0/LICENSE',
 'SmolVLM2-model-card.md':'https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct/resolve/main/README.md',
 'NIMA-conversion-model-card.md':'https://huggingface.co/litert-community/NIMA-LiteRT/resolve/5e27e756cf38d2e738ee0c7c5d6f1c43f2073038/README.md'
}
for name,url in files.items():
 with urllib.request.urlopen(url,timeout=90) as response: data=response.read()
 assert len(data)>100,name
 (folder/name).write_bytes(data)
# SmolVLM2 explicitly declares Apache 2.0; include its full text in addition to its card.
(folder/'SmolVLM2-Apache-2.0.txt').write_bytes((folder/'Qwen3-Apache-2.0.txt').read_bytes())
