from setuptools import setup, find_packages

setup(
    name="gitoracle-cli",
    version="1.0.0",
    packages=find_packages(),
    include_package_data=True,
    install_requires=[
        "click",
        "requests",
        "rich"
    ],
    entry_points={
        "console_scripts": [
            "gitoracle=gitOracle.main:cli",
        ],
    },
)
